package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.SystemSettingsDto;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BalancingService {

  private static final Logger logger = LoggerFactory.getLogger(BalancingService.class);
  private static final String DEVICE_TYPE_SWITCHABLE_APPLIANCE = "SWITCHABLE_APPLIANCE";
  private static final int DEFAULT_POWER_ON_MARGIN_WATTS = 100;

  private final ApiServiceClient apiServiceClient;
  private final DeviceStatusService deviceStatusService;

  /**
   * -- SETTER -- Setter for ShellyService to avoid circular dependency. Spring will inject this
   * after construction.
   */
  @Setter private ShellyService shellyService; // Lazy injection to avoid circular dependency

  public BalancingService(
      ApiServiceClient apiServiceClient, DeviceStatusService deviceStatusService) {
    this.apiServiceClient = apiServiceClient;
    this.deviceStatusService = deviceStatusService;
  }

  /**
   * Core power balancing logic. This method is triggered when a POWER_MONITOR device reports its
   * status.
   *
   * @param mqttPrefix The MQTT prefix of the power monitor device
   * @param powerMonitorStatus The JSON status payload from the power monitor
   */
  public void balancePower(String mqttPrefix, JsonNode powerMonitorStatus) {
    try {
      logger.debug("Starting power balancing for MQTT prefix: {}", mqttPrefix);

      // Step 1: Extract current total power consumption from the power monitor status
      Double currentTotalPower = extractPowerConsumption(powerMonitorStatus);
      if (currentTotalPower == null) {
        logger.error("Failed to extract power consumption from status. Aborting balancing.");
        return;
      }
      logger.debug("Current total power consumption: {} W", currentTotalPower);

      // Step 2: Fetch the system state (settings and devices) from the API
      Optional<SystemStateDto> systemStateOpt =
          apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix);
      if (systemStateOpt.isEmpty()) {
        logger.error(
            "System state not found for MQTT prefix: {}. Cannot perform balancing.", mqttPrefix);
        return;
      }

      SystemStateDto systemState = systemStateOpt.get();
      SystemSettingsDto settings = systemState.getSystemSettings();
      List<DeviceDto> allDevices = systemState.getDevices();

      if (settings == null || settings.getPowerLimitWatts() == null) {
        logger.error("System settings or power limit not configured. Aborting balancing.");
        return;
      }

      int powerLimitWatts = settings.getPowerLimitWatts();
      logger.debug("Power limit configured: {} W", powerLimitWatts);

      // Step 3: Check if a system is overloaded or has available margin
      if (currentTotalPower > powerLimitWatts) {
        // OVERLOAD ACTION: Turn off devices to reduce power consumption
        logger.warn(
            "OVERLOAD DETECTED! Current power: {} W exceeds limit: {} W",
            currentTotalPower,
            powerLimitWatts);

        // Step 4: Filter switchable appliances that are online and turned on
        List<DeviceDto> switchableDevices =
            allDevices.stream()
                .filter(device -> DEVICE_TYPE_SWITCHABLE_APPLIANCE.equals(device.getDeviceType()))
                .filter(this::isDeviceOnlineAndOn)
                .filter(device -> device.getWattage() != null && device.getWattage() > 0)
                .sorted(
                    Comparator.comparing(DeviceDto::getPriority, Comparator.reverseOrder())
                        .thenComparing(DeviceDto::getId))
                .toList();

        if (switchableDevices.isEmpty()) {
          logger.warn(
              "No switchable appliances available to turn off. Cannot reduce power consumption.");
          return;
        }

        logger.info(
            "Found {} switchable appliances that can be turned off: {}",
            switchableDevices.size(),
            switchableDevices.stream().map(DeviceDto::getName).collect(Collectors.toList()));

        // Step 5: Turn off devices one by one until power is below the limit
        double remainingPower = currentTotalPower;
        for (DeviceDto device : switchableDevices) {
          if (remainingPower <= powerLimitWatts) {
            logger.info("Power is now within limit. Stopping device shutdown.");
            break;
          }

          int deviceWattage = device.getWattage();

          logger.warn(
              "Turning OFF device '{}' (ID: {}, Priority: {}) to save approximately {} W",
              device.getName(),
              device.getId(),
              device.getPriority(),
              deviceWattage);

          // Send command to turn off the device
          if (shellyService != null) {
            shellyService.sendCommand(device.getMqttPrefix(), false);
            // Update control state to indicate balancer disabled this device
            deviceStatusService.updateControlState(
                device.getId(), DeviceControlState.DISABLED_BY_BALANCER);
          } else {
            logger.error("ShellyService not initialized. Cannot send command to device.");
            continue;
          }

          // Reduce the remaining power by the device's wattage
          remainingPower -= deviceWattage;

          logger.info(
              "Device '{}' turned off. Estimated remaining power: {} W",
              device.getName(),
              remainingPower);
        }

        if (remainingPower > powerLimitWatts) {
          logger.error(
              "POWER BALANCING FAILED! All available devices ({}) were turned off, but power ({}) still exceeds limit ({}). "
                  + "Power source shutdown is likely. Check system load or device wattage configurations in sbps-api.",
              switchableDevices.stream().map(DeviceDto::getName).toList(),
              remainingPower,
              powerLimitWatts);
        } else {
          logger.info("Power balancing completed successfully. System is now within limits.");
        }

      } else {
        // RESTORE ACTION: Try to restore devices that were disabled by the balancer
        logger.debug(
            "System is within power limit ({} W <= {} W). Checking for devices to restore.",
            currentTotalPower,
            powerLimitWatts);

        // Step 1. Calculate available margin (with safety buffer)
        int powerOnMargin =
            settings.getPowerOnMarginWatts() != null
                ? settings.getPowerOnMarginWatts()
                : DEFAULT_POWER_ON_MARGIN_WATTS;
        double availableMargin = powerLimitWatts - currentTotalPower - powerOnMargin;

        if (availableMargin <= 0) {
          logger.debug(
              "No available margin for restoring devices. Current: {} W, Limit: {} W, Margin: {} W",
              currentTotalPower,
              powerLimitWatts,
              powerOnMargin);
          return;
        }

        logger.debug(
            "Available power margin for restoration: {} W (after {} W safety buffer)",
            availableMargin,
            powerOnMargin);

        // Step 2. Find devices disabled by balancer
        List<DeviceDto> devicesToRestore =
            allDevices.stream()
                .filter(device -> DEVICE_TYPE_SWITCHABLE_APPLIANCE.equals(device.getDeviceType()))
                .filter(device -> isDeviceDisabledByBalancer(device.getId()))
                .sorted(
                    Comparator.comparing(DeviceDto::getPriority)) // Sort by priority (1, 2, 3...)
                .toList();

        if (devicesToRestore.isEmpty()) {
          logger.debug("No devices disabled by balancer found to restore.");
          return;
        }

        logger.info(
            "Found {} devices disabled by balancer: {}",
            devicesToRestore.size(),
            devicesToRestore.stream().map(DeviceDto::getName).collect(Collectors.toList()));

        // Step 3. Try to restore devices one by one
        for (DeviceDto device : devicesToRestore) {
          int deviceWattage = device.getWattage() != null ? device.getWattage() : 0;

          if (deviceWattage > 0 && availableMargin >= deviceWattage) {
            logger.info(
                "Restoring power for device '{}' (Priority: {}, Wattage: {} W). Sufficient margin available.",
                device.getName(),
                device.getPriority(),
                deviceWattage);

            if (shellyService != null) {
              shellyService.sendCommand(device.getMqttPrefix(), true);
              deviceStatusService.updateControlState(device.getId(), DeviceControlState.ENABLED);
            } else {
              logger.error("ShellyService not initialized. Cannot send command to device.");
              continue;
            }

            // Reduce available margin
            availableMargin -= deviceWattage;
            logger.info(
                "Device '{}' restored. Remaining available margin: {} W",
                device.getName(),
                availableMargin);
          } else if (deviceWattage > 0) {
            logger.debug(
                "Cannot restore device '{}' (Wattage: {} W). Insufficient margin: {} W. Stopping restoration.",
                device.getName(),
                deviceWattage,
                availableMargin);
            break; // Stop trying to restore more devices
          }
        }
      }

    } catch (Exception e) {
      logger.error("Error during power balancing for MQTT prefix: {}", mqttPrefix, e);
    }
  }

  /**
   * Extracts the power consumption value from the power monitor status JSON. Shelly plugs typically
   * provide this under the "apower" field.
   *
   * @param statusJson The status JSON from the power monitor
   * @return Power consumption in watts, or null if not found
   */
  private Double extractPowerConsumption(JsonNode statusJson) {
    try {
      // Try to get "apower" field (active power in watts)
      if (statusJson.has("apower")) {
        return statusJson.get("apower").asDouble();
      }

      // Fallback: try the "power" field
      if (statusJson.has("power")) {
        return statusJson.get("power").asDouble();
      }

      logger.error("Power consumption field not found in status JSON: {}", statusJson);
      return null;

    } catch (Exception e) {
      logger.error("Error extracting power consumption from JSON", e);
      return null;
    }
  }

  /**
   * Checks if a device is currently online and turned on.
   *
   * @param device The device to check
   * @return true if a device is online and on, false otherwise
   */
  private boolean isDeviceOnlineAndOn(DeviceDto device) {
    try {
      Optional<DeviceStatus> statusOpt = deviceStatusService.findByDeviceId(device.getId());
      if (statusOpt.isEmpty()) {
        logger.debug("No status found for device: {}", device.getName());
        return false;
      }

      DeviceStatus status = statusOpt.get();

      // Check if the device is online
      if (status.getLastOnline() == null || !status.getLastOnline()) {
        logger.debug("Device {} is offline", device.getName());
        return false;
      }

      // Check if the device is turned on by parsing the status JSON
      String statusJson = status.getLastStatusJson();
      if (statusJson == null) {
        logger.debug("No status JSON for device: {}", device.getName());
        return false;
      }

      JsonNode statusNode = deviceStatusService.getStatusAsJsonNode(device.getId());
      if (statusNode != null && statusNode.has("output")) {
        boolean isOn = statusNode.get("output").asBoolean();
        logger.debug("Device {} is {}", device.getName(), isOn ? "ON" : "OFF");
        return isOn;
      }

      return false;

    } catch (Exception e) {
      logger.error("Error checking device status for: {}", device.getName(), e);
      return false;
    }
  }

  /**
   * Checks if a device is currently disabled by the balancer.
   *
   * @param deviceId The device ID to check
   * @return true if the balancer disables the device, false otherwise
   */
  private boolean isDeviceDisabledByBalancer(Long deviceId) {
    try {
      DeviceControlState controlState = deviceStatusService.getControlState(deviceId);
      return DeviceControlState.DISABLED_BY_BALANCER.equals(controlState);
    } catch (Exception e) {
      logger.error("Error checking control state for device ID: {}", deviceId, e);
      return false;
    }
  }
}
