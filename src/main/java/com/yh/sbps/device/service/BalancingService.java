package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.SystemSettingsDto;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BalancingService {

  private static final Logger logger = LoggerFactory.getLogger(BalancingService.class);
  private static final String DEVICE_TYPE_SWITCHABLE_APPLIANCE = "SWITCHABLE_APPLIANCE";
  private static final String DEVICE_TYPE_POWER_MONITOR = "POWER_MONITOR";

  private final ApiServiceClient apiServiceClient;
  private final DeviceStatusService deviceStatusService;
  private ShellyService shellyService; // Lazy injection to avoid circular dependency

  public BalancingService(
      ApiServiceClient apiServiceClient, DeviceStatusService deviceStatusService) {
    this.apiServiceClient = apiServiceClient;
    this.deviceStatusService = deviceStatusService;
  }

  /**
   * Setter for ShellyService to avoid circular dependency. Spring will inject this after
   * construction.
   */
  public void setShellyService(ShellyService shellyService) {
    this.shellyService = shellyService;
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
      logger.info("Starting power balancing for MQTT prefix: {}", mqttPrefix);

      // Step 1: Extract current total power consumption from the power monitor status
      Double currentTotalPower = extractPowerConsumption(powerMonitorStatus);
      if (currentTotalPower == null) {
        logger.error("Failed to extract power consumption from status. Aborting balancing.");
        return;
      }
      logger.info("Current total power consumption: {} W", currentTotalPower);

      // Step 2: Fetch the system state (settings + devices) from the API
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
      logger.info("Power limit configured: {} W", powerLimitWatts);

      // Step 3: Check if system is overloaded
      if (currentTotalPower <= powerLimitWatts) {
        logger.debug(
            "System is within power limit ({} W <= {} W). No action needed.",
            currentTotalPower,
            powerLimitWatts);
        return;
      }

      logger.warn(
          "OVERLOAD DETECTED! Current power: {} W exceeds limit: {} W",
          currentTotalPower,
          powerLimitWatts);

      // Step 4: Filter switchable appliances that are online and turned on
      List<DeviceDto> switchableDevices =
          allDevices.stream()
              .filter(device -> DEVICE_TYPE_SWITCHABLE_APPLIANCE.equals(device.getType()))
              .filter(this::isDeviceOnlineAndOn)
              .sorted(
                  Comparator.comparing(DeviceDto::getPriority, Comparator.reverseOrder())
                      .thenComparing(DeviceDto::getId))
              .collect(Collectors.toList());

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

        // Get device's configured wattage (we'll use a default if not available)
        int deviceWattage = getDeviceWattage(device);

        logger.warn(
            "Turning OFF device '{}' (ID: {}, Priority: {}) to save approximately {} W",
            device.getName(),
            device.getId(),
            device.getPriority(),
            deviceWattage);

        // Send command to turn off the device
        if (shellyService != null) {
          shellyService.sendCommand(device.getMqttPrefix(), false);
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
        logger.warn(
            "All available devices have been turned off, but power ({} W) still exceeds limit ({} W)",
            remainingPower,
            powerLimitWatts);
      } else {
        logger.info("Power balancing completed successfully. System is now within limits.");
      }

    } catch (Exception e) {
      logger.error("Error during power balancing for MQTT prefix: {}", mqttPrefix, e);
    }
  }

  /**
   * Extracts the power consumption value from the power monitor status JSON. Shelly plugs typically
   * provide this under "apower" field.
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

      // Fallback: try "power" field
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
   * @return true if device is online and on, false otherwise
   */
  private boolean isDeviceOnlineAndOn(DeviceDto device) {
    try {
      Optional<DeviceStatus> statusOpt = deviceStatusService.findByDeviceId(device.getId());
      if (statusOpt.isEmpty()) {
        logger.debug("No status found for device: {}", device.getName());
        return false;
      }

      DeviceStatus status = statusOpt.get();

      // Check if device is online
      if (status.getLastOnline() == null || !status.getLastOnline()) {
        logger.debug("Device {} is offline", device.getName());
        return false;
      }

      // Check if device is turned on by parsing the status JSON
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
   * Gets the configured wattage for a device. This could be enhanced to read from device
   * configuration in the future.
   *
   * @param device The device
   * @return Estimated wattage (default: 1000W for now)
   */
  private int getDeviceWattage(DeviceDto device) {
    // TODO: In the future, this could be read from device configuration
    // For now, we'll use a default value or try to read from the current status
    return 1000; // Default 1000W
  }
}
