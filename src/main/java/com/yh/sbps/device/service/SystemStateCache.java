package com.yh.sbps.device.service;

import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemStateCache {

  private static final Logger logger = LoggerFactory.getLogger(SystemStateCache.class);
  private final ApiServiceClient apiServiceClient;
  private final ShellyService shellyService;

  // key - mqttPrefix of monitor
  @Getter private final Map<String, SystemStateDto> stateCache = new ConcurrentHashMap<>();

  // key - mqttPrefix of any device, value - mqttPrefix of monitor
  private final Map<String, String> deviceToMonitorMap = new ConcurrentHashMap<>();

  public SystemStateCache(ApiServiceClient apiServiceClient, ShellyService shellyService) {
    this.apiServiceClient = apiServiceClient;
    this.shellyService = shellyService;
  }

  public Optional<SystemStateDto> getState(String monitorMqttPrefix) {
    return Optional.ofNullable(stateCache.get(monitorMqttPrefix));
  }

  public void refreshState(String monitorMqttPrefix) {
    logger.info("Refreshing system state for monitor prefix: {}", monitorMqttPrefix);
    try {
      Optional<SystemStateDto> systemStateOpt =
          apiServiceClient.getSystemStateByMqttPrefix(monitorMqttPrefix);

      if (systemStateOpt.isPresent()) {
        SystemStateDto systemState = systemStateOpt.get();
        stateCache.put(monitorMqttPrefix, systemState);

        updateDeviceToMonitorMap(monitorMqttPrefix, systemState);

        if (systemState.getDevices() != null) {
          logger.debug(
              "Refreshing ShellyService cache for {} devices.", systemState.getDevices().size());
          for (DeviceDto device : systemState.getDevices()) {
            shellyService.refreshDeviceCache(device);
          }
        }

        logger.info(
            "Successfully refreshed state for monitor: {}. {} devices loaded.",
            monitorMqttPrefix,
            systemState.getDevices() != null ? systemState.getDevices().size() : 0);
      } else {
        logger.warn(
            "Could not find system state for monitor prefix: {}. Removing from cache.",
            monitorMqttPrefix);
        stateCache.remove(monitorMqttPrefix);
        updateDeviceToMonitorMap(monitorMqttPrefix, null);
      }
    } catch (Exception e) {
      logger.error("Failed to refresh state for monitor prefix: {}", monitorMqttPrefix, e);
    }
  }

  public void removeDevice(String deviceMqttPrefix) {
    logger.info("Removing device with prefix: {} from cache", deviceMqttPrefix);
    String monitorPrefix = deviceToMonitorMap.remove(deviceMqttPrefix);
    if (monitorPrefix != null) {
      stateCache.computeIfPresent(
          monitorPrefix,
          (key, oldState) -> {
            logger.info(
                "Updating cached state for monitor {} to remove device {}", key, deviceMqttPrefix);

            List<DeviceDto> newDeviceList =
                oldState.getDevices().stream()
                    .filter(device -> !deviceMqttPrefix.equals(device.getMqttPrefix()))
                    .collect(Collectors.toList());

            return new SystemStateDto(oldState.getSystemSettings(), newDeviceList);
          });

    } else {
      if (stateCache.remove(deviceMqttPrefix) != null) {
        logger.info("Monitor {} removed from state cache.", deviceMqttPrefix);
      }
      updateDeviceToMonitorMap(deviceMqttPrefix, null);
    }
  }

  private void updateDeviceToMonitorMap(String monitorPrefix, SystemStateDto state) {
    deviceToMonitorMap.values().removeIf(v -> v.equals(monitorPrefix));

    if (state != null && state.getDevices() != null) {
      for (DeviceDto device : state.getDevices()) {
        if (device.getMqttPrefix() != null) {
          deviceToMonitorMap.put(device.getMqttPrefix(), monitorPrefix);
        }
      }
    }
  }
}
