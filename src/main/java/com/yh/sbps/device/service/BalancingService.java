package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.SystemSettingsDto;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap; // <-- Новий імпорт
import java.util.stream.Collectors;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BalancingService {

  private static final Logger logger = LoggerFactory.getLogger(BalancingService.class);
  private static final String SHELLY_SERVICE_ERROR =
      "ShellyService not wired in BalancingService!)";
  private static final String SWITCHABLE_APPLIANCE = "SWITCHABLE_APPLIANCE";
  private static final int DEFAULT_POWER_ON_MARGIN_WATTS = 100;

  private final DeviceStatusService deviceStatusService;
  private final Map<String, LocalDateTime> lastOverloadTimeByMqttPrefix = new ConcurrentHashMap<>();
  private final SystemStateCache systemStateCache;
  @Setter private ShellyService shellyService; // Lazy injection

  public BalancingService(
      DeviceStatusService deviceStatusService, SystemStateCache systemStateCache) {
    this.systemStateCache = systemStateCache;
    this.deviceStatusService = deviceStatusService;
  }

  public void balancePower(String mqttPrefix, JsonNode powerMonitorStatus) {
    try {
      // Step 1: Get current power
      Double currentTotalPower = extractPowerConsumption(powerMonitorStatus);
      if (currentTotalPower == null) {
        logger.error("Failed to extract power. Aborting balancing.");
        return;
      }

      // Step 2: Get system state
      Optional<SystemStateDto> systemStateOpt = systemStateCache.getState(mqttPrefix);
      if (systemStateOpt.isEmpty()) {
        logger.error("System state not found in cache for {}. Aborting balancing.", mqttPrefix);
        // TODO maybe worth try to update state here?
        // systemStateCache.refreshState(mqttPrefix);
        return;
      }

      SystemStateDto systemState = systemStateOpt.get();
      SystemSettingsDto settings = systemState.getSystemSettings();
      if (settings == null || settings.getPowerLimitWatts() == null) {
        logger.error("Power limit not configured. Aborting balancing.");
        return;
      }
      int powerLimitWatts = settings.getPowerLimitWatts();
      int powerOnMargin =
          settings.getPowerOnMarginWatts() != null
              ? settings.getPowerOnMarginWatts()
              : DEFAULT_POWER_ON_MARGIN_WATTS;

      // Step 3: OVERLOAD logic
      double powerAfterOverload =
          handleOverload(currentTotalPower, powerLimitWatts, systemState.getDevices(), mqttPrefix);

      // Step 4: PREVENT DOWNTIME logic
      double powerAfterDowntimePrevention =
          handlePreventDowntime(
              powerAfterOverload, powerLimitWatts, powerOnMargin, systemState.getDevices());

      int overloadCooldownSeconds =
          settings.getOverloadCooldownSeconds() != null ? settings.getOverloadCooldownSeconds() : 0;
      handleRestore(
          powerAfterDowntimePrevention,
          powerLimitWatts,
          powerOnMargin,
          overloadCooldownSeconds,
          systemState.getDevices(),
          mqttPrefix);

    } catch (Exception e) {
      logger.error("Error during power balancing for MQTT prefix: {}", mqttPrefix, e);
    }
  }

  public void clearOverloadCooldown(String mqttPrefix) {
    lastOverloadTimeByMqttPrefix.remove(mqttPrefix);
    logger.info("Cleared overload cooldown timer for prefix: {}", mqttPrefix);
  }

  private double getActualPower(Long deviceId) {
    try {
      JsonNode statusNode = deviceStatusService.getStatusAsJsonNode(deviceId);
      if (statusNode != null && statusNode.has("apower")) {
        return statusNode.get("apower").asDouble(0.0);
      }
    } catch (Exception e) {
      logger.error("Error getting actual power for device {}", deviceId, e);
    }
    return 0.0;
  }

  private double handleOverload(
      double currentTotalPower,
      int powerLimitWatts,
      List<DeviceDto> allDevices,
      String mqttPrefix) {
    if (currentTotalPower <= powerLimitWatts) {
      logger.debug(
          "System OK. Current power: {} W, Limit: {} W", currentTotalPower, powerLimitWatts);
      return currentTotalPower;
    }

    lastOverloadTimeByMqttPrefix.put(mqttPrefix, LocalDateTime.now());

    logger.warn(
        "OVERLOAD! (Prefix: {}) Current power: {} W > Limit: {} W. Starting shutdown...",
        mqttPrefix,
        currentTotalPower,
        powerLimitWatts);

    List<SheddableDevice> devicesToTurnOff =
        allDevices.stream()
            .filter(device -> SWITCHABLE_APPLIANCE.equals(device.getDeviceType()))
            .filter(this::isDeviceOnlineAndOn)
            .map(
                device -> {
                  double actualPower = getActualPower(device.getId());
                  return new SheddableDevice(device, actualPower);
                })
            .filter(sd -> sd.actualPower() > 0)
            .sorted(
                Comparator.comparing((SheddableDevice sd) -> sd.device().getPriority())
                    .reversed() // 10, 9, 8...
                    .thenComparing(sd -> sd.device().getId()))
            .toList();

    double powerToShed = currentTotalPower - powerLimitWatts;
    double powerShed = 0;

    for (SheddableDevice sd : devicesToTurnOff) {
      if (powerShed >= powerToShed) {
        logger.info("Sufficient power shed ({}) to meet limit. Stopping shutdown.", powerShed);
        break;
      }

      DeviceDto device = sd.device();
      double actualPower = sd.actualPower();

      logger.warn(
          "Shedding load: Turning OFF device '{}' (Priority: {}), freeing {} W",
          device.getName(),
          device.getPriority(),
          actualPower);

      turnOffDevice(device);
      powerShed += actualPower;
    }

    double newTotalPower = currentTotalPower - powerShed;
    if (newTotalPower > powerLimitWatts) {
      logger.error(
          "OVERLOAD FAILED! Shed {} W, but new power {} W is still > limit {} W.",
          powerShed,
          newTotalPower,
          powerLimitWatts);
    } else {
      logger.info("Overload handled. Shed {} W. New power: {} W.", powerShed, newTotalPower);
    }
    return newTotalPower;
  }

  private double handlePreventDowntime(
      double currentTotalPower,
      int powerLimitWatts,
      int powerOnMargin,
      List<DeviceDto> allDevices) {

    List<DeviceDto> devicesToForceOn =
        allDevices.stream()
            .filter(
                d ->
                    d.isPreventDowntime()
                        && isDeviceDisabledByBalancer(d.getId())
                        && hasDowntimeExpired(d))
            .sorted(Comparator.comparing(DeviceDto::getPriority)) // 0, 1, 2...
            .toList();

    if (devicesToForceOn.isEmpty()) {
      return currentTotalPower; // No devices to force on
    }

    logger.info(
        "PREVENT DOWNTIME: Found {} critical devices to turn on: {}",
        devicesToForceOn.size(),
        devicesToForceOn.stream().map(DeviceDto::getName).toList());

    double availableMargin = powerLimitWatts - currentTotalPower - powerOnMargin;
    double powerAfterChanges = currentTotalPower;

    // 2. Find "sacrificial" devices that can be turned off
    List<DeviceDto> sacrificialDevices =
        allDevices.stream()
            .filter(d -> SWITCHABLE_APPLIANCE.equals(d.getDeviceType()))
            .filter(this::isDeviceOnlineAndOn)
            .filter(
                d -> !devicesToForceOn.contains(d)) // Don't sacrifice a device we want to turn on
            .sorted(
                Comparator.comparing(
                    DeviceDto::getPriority, Comparator.reverseOrder()) // 10, 9, 8...
                )
            .collect(Collectors.toCollection(ArrayList::new)); // Mutable list

    // 3. Try to turn on each critical device
    for (DeviceDto deviceToOn : devicesToForceOn) {
      int powerNeeded = deviceToOn.getWattage() != null ? deviceToOn.getWattage() : 0;
      if (powerNeeded == 0) continue;

      if (availableMargin >= powerNeeded) {
        // We have enough margin, just turn it on
        logger.info(
            "Forcing ON '{}' ({} W). Sufficient margin available.",
            deviceToOn.getName(),
            powerNeeded);
        turnOnDevice(deviceToOn);
        availableMargin -= powerNeeded;
        powerAfterChanges += powerNeeded;
      } else {
        // Not enough margin, must sacrifice other devices
        int powerToFree = (int) (powerNeeded - availableMargin);
        logger.warn(
            "Forcing ON '{}' ({} W). Need to free {} W.",
            deviceToOn.getName(),
            powerNeeded,
            powerToFree);

        List<DeviceDto> devicesToSacrifice =
            findDevicesToSacrifice(sacrificialDevices, powerToFree);

        if (!devicesToSacrifice.isEmpty()) {
          int powerFreed = 0;
          for (DeviceDto deviceToOff : devicesToSacrifice) {
            logger.warn(
                "Sacrificing device '{}' (Priority: {}) to free up {} W.",
                deviceToOff.getName(),
                deviceToOff.getPriority(),
                deviceToOff.getWattage());
            turnOffDevice(deviceToOff);
            powerFreed += deviceToOff.getWattage();
            sacrificialDevices.remove(deviceToOff); // Remove from available list
          }

          availableMargin += powerFreed;
          powerAfterChanges -= powerFreed;

          // Now turn on the critical device
          logger.info(
              "Freed {} W. Turning ON critical device '{}'.", powerFreed, deviceToOn.getName());
          turnOnDevice(deviceToOn);
          availableMargin -= powerNeeded;
          powerAfterChanges += powerNeeded;

        } else {
          logger.error(
              "CANNOT FORCE ON '{}': No sacrificial devices found to free {} W.",
              deviceToOn.getName(),
              powerToFree);
        }
      }
    }
    return powerAfterChanges;
  }

  private List<DeviceDto> findDevicesToSacrifice(
      List<DeviceDto> sacrificialDevices, int powerToFree) {
    List<DeviceDto> devicesToSacrifice = new ArrayList<>();
    int powerFreed = 0;
    for (DeviceDto device : sacrificialDevices) {
      devicesToSacrifice.add(device);
      powerFreed += device.getWattage() != null ? device.getWattage() : 0;
      if (powerFreed >= powerToFree) {
        return devicesToSacrifice; // Found enough devices
      }
    }
    return Collections.emptyList(); // Not enough power can be freed
  }

  private void handleRestore(
      double currentTotalPower,
      int powerLimitWatts,
      int powerOnMargin,
      int overloadCooldownSeconds,
      List<DeviceDto> allDevices,
      String mqttPrefix) {
    LocalDateTime lastOverloadTime = lastOverloadTimeByMqttPrefix.get(mqttPrefix);

    // Enforce overload cooldown: skip restoring if not enough time has passed
    if (overloadCooldownSeconds > 0 && lastOverloadTime != null) {
      long secondsSinceOverload = ChronoUnit.SECONDS.between(lastOverloadTime, LocalDateTime.now());
      if (secondsSinceOverload < overloadCooldownSeconds) {
        logger.debug(
            "RESTORE cooldown active for {}: {}s since overload (< {}s). Skipping restore.",
            mqttPrefix,
            secondsSinceOverload,
            overloadCooldownSeconds);
        return;
      } else {
        lastOverloadTimeByMqttPrefix.remove(mqttPrefix);
      }
    }

    double availableMargin = powerLimitWatts - currentTotalPower - powerOnMargin;
    if (availableMargin <= 0) {
      logger.debug("No available margin for restoration.");
      return;
    }

    logger.debug(
        "RESTORE: System {} has {} W available margin. Checking for devices to restore...",
        mqttPrefix,
        availableMargin);

    // Find devices disabled BY BALANCER (and not by user)
    List<DeviceDto> devicesToRestore =
        allDevices.stream()
            .filter(device -> SWITCHABLE_APPLIANCE.equals(device.getDeviceType()))
            .filter(device -> isDeviceDisabledByBalancer(device.getId()))
            .filter(device -> !isDeviceOnlineAndOn(device)) // Ensure it's actually off
            .sorted(Comparator.comparing(DeviceDto::getPriority)) // 0, 1, 2...
            .toList();

    if (devicesToRestore.isEmpty()) {
      logger.debug("No devices disabled by balancer found to restore.");
      return;
    }

    for (DeviceDto device : devicesToRestore) {
      int deviceWattage = device.getWattage() != null ? device.getWattage() : 0;
      if (deviceWattage > 0 && availableMargin >= deviceWattage) {
        logger.info(
            "Restoring power for device '{}' (Priority: {}, {} W). Margin: {} W",
            device.getName(),
            device.getPriority(),
            deviceWattage,
            availableMargin);
        turnOnDevice(device);
        availableMargin -= deviceWattage;
      } else if (deviceWattage > 0) {
        logger.debug(
            "Cannot restore '{}' ({} W). Insufficient margin: {} W.",
            device.getName(),
            deviceWattage,
            availableMargin);
        break; // Stop restoring
      }
    }
  }

  private void turnOnDevice(DeviceDto device) {
    Objects.requireNonNull(shellyService, SHELLY_SERVICE_ERROR);
    shellyService.sendCommand(device.getMqttPrefix(), true);
    deviceStatusService.updateControlState(device.getId(), DeviceControlState.ENABLED);
  }

  private void turnOffDevice(DeviceDto device) {
    Objects.requireNonNull(shellyService, SHELLY_SERVICE_ERROR);
    shellyService.sendCommand(device.getMqttPrefix(), false);
    deviceStatusService.updateControlState(device.getId(), DeviceControlState.DISABLED_BY_BALANCER);
  }

  private boolean hasDowntimeExpired(DeviceDto device) {
    if (device.getMaxDowntimeMinutes() == null || device.getMaxDowntimeMinutes() <= 0) {
      return false; // Downtime isn't configured
    }
    LocalDateTime disabledAt = deviceStatusService.getBalancerDisabledAt(device.getId());
    if (disabledAt == null) {
      return false; // Not disabled by balancer
    }

    long minutesOff = ChronoUnit.MINUTES.between(disabledAt, LocalDateTime.now());
    boolean expired = minutesOff >= device.getMaxDowntimeMinutes();
    if (expired) {
      logger.warn(
          "Downtime expired for '{}'. Off for {} min (Max: {} min).",
          device.getName(),
          minutesOff,
          device.getMaxDowntimeMinutes());
    }
    return expired;
  }

  private Double extractPowerConsumption(JsonNode statusJson) {
    try {
      if (statusJson.has("apower")) return statusJson.get("apower").asDouble();
      if (statusJson.has("power")) return statusJson.get("power").asDouble();
      logger.error("Power field not found in status JSON: {}", statusJson);
      return null;
    } catch (Exception e) {
      logger.error("Error extracting power from JSON", e);
      return null;
    }
  }

  private boolean isDeviceOnlineAndOn(DeviceDto device) {
    try {
      Optional<DeviceStatus> statusOpt = deviceStatusService.findByDeviceId(device.getId());
      if (statusOpt.isEmpty()) return false;
      DeviceStatus status = statusOpt.get();
      if (status.getLastOnline() == null || !status.getLastOnline()) return false;

      JsonNode statusNode = deviceStatusService.getStatusAsJsonNode(device.getId());
      return statusNode != null && statusNode.has("output") && statusNode.get("output").asBoolean();
    } catch (Exception e) {
      logger.error("Error checking device status for: {}", device.getName(), e);
      return false;
    }
  }

  private boolean isDeviceDisabledByBalancer(Long deviceId) {
    try {
      DeviceControlState controlState = deviceStatusService.getControlState(deviceId);
      return DeviceControlState.DISABLED_BY_BALANCER.equals(controlState);
    } catch (Exception e) {
      logger.error("Error checking control state for device ID: {}", deviceId, e);
      return false;
    }
  }

  private record SheddableDevice(DeviceDto device, double actualPower) {}
}
