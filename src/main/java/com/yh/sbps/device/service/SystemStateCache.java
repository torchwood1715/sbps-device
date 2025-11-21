package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.BlackoutStatsDto;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.DeviceType;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemStateCache {

  private static final Logger logger = LoggerFactory.getLogger(SystemStateCache.class);
  private final ApiServiceClient apiServiceClient;
  private final ShellyService shellyService;
  private final DeviceRealtimeStateCache deviceRealtimeStateCache;
  private final ObjectMapper objectMapper = new ObjectMapper();

  // key - mqttPrefix of monitor
  @Getter private final Map<String, SystemStateDto> stateCache = new ConcurrentHashMap<>();

  // key - mqttPrefix of any device, value - mqttPrefix of monitor
  @Getter private final Map<String, String> deviceToMonitorMap = new ConcurrentHashMap<>();

  // key - mqttPrefix of monitor, value - grid status
  private final Map<String, Boolean> gridStatusCache = new ConcurrentHashMap<>();

  private final Map<String, BlackoutSession> blackoutSessions = new ConcurrentHashMap<>();

  public SystemStateCache(
      ApiServiceClient apiServiceClient,
      ShellyService shellyService,
      DeviceRealtimeStateCache deviceRealtimeStateCache) {
    this.apiServiceClient = apiServiceClient;
    this.shellyService = shellyService;
    this.deviceRealtimeStateCache = deviceRealtimeStateCache;
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
        boolean isGridAvailable = isGridAvailable(monitorMqttPrefix);
        systemState.setGridPowerAvailable(isGridAvailable);
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
        gridStatusCache.remove(monitorMqttPrefix);
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

            return new SystemStateDto(
                oldState.getSystemSettings(), newDeviceList, oldState.isGridPowerAvailable());
          });

    } else {
      if (stateCache.remove(deviceMqttPrefix) != null) {
        logger.info("Monitor {} removed from state cache.", deviceMqttPrefix);
        gridStatusCache.remove(deviceMqttPrefix);
      }
      updateDeviceToMonitorMap(deviceMqttPrefix, null);
    }
  }

  private Double getDeviceTotalEnergy(Long deviceId) {
    Optional<DeviceStatus> statusOpt = deviceRealtimeStateCache.get(deviceId);
    if (statusOpt.isEmpty() || statusOpt.get().getLastStatusJson() == null) return null;
    try {
      JsonNode node = objectMapper.readTree(statusOpt.get().getLastStatusJson());
      if (node.has("aenergy") && node.get("aenergy").has("total")) {
        return node.get("aenergy").get("total").asDouble();
      }
    } catch (Exception e) {
      logger.error("Error parsing energy", e);
    }
    return null;
  }

  public BlackoutStatsDto getBlackoutStats(String monitorMqttPrefix) {
    BlackoutSession session = blackoutSessions.get(monitorMqttPrefix);
    if (session == null) {
      return new BlackoutStatsDto(false, 0.0, 0);
    }
    double consumed = 0.0;
    if (session.getPowerMonitorId() != null && session.getStartEnergy() != null) {
      Double currentTotal = getDeviceTotalEnergy(session.getPowerMonitorId());
      if (currentTotal != null && currentTotal >= session.getStartEnergy()) {
        consumed = currentTotal - session.getStartEnergy();
      }
    }
    long durationSeconds =
        Duration.between(session.getStartTime(), LocalDateTime.now()).getSeconds();
    return new BlackoutStatsDto(true, consumed, durationSeconds);
  }

  public void updateGridStatus(String monitorMqttPrefix, boolean isAvailable) {
    if (monitorMqttPrefix == null) return;

    boolean oldStatus = isGridAvailable(monitorMqttPrefix);
    gridStatusCache.put(monitorMqttPrefix, isAvailable);

    stateCache.computeIfPresent(
        monitorMqttPrefix,
        (key, state) -> {
          state.setGridPowerAvailable(isAvailable);
          return state;
        });

    if (oldStatus && !isAvailable) {
      logger.info("Blackout started for monitor {}. Snapshotting...", monitorMqttPrefix);
      findAndSnapshotPowerMonitor(monitorMqttPrefix);
    } else if (!oldStatus && isAvailable) {
      logger.info("Grid restored for monitor {}. Clearing stats.", monitorMqttPrefix);
      blackoutSessions.remove(monitorMqttPrefix);
    }
  }

  public boolean isGridAvailable(String monitorMqttPrefix) {
    if (monitorMqttPrefix == null) return true;
    return gridStatusCache.getOrDefault(monitorMqttPrefix, true);
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

  private void findAndSnapshotPowerMonitor(String monitorMqttPrefix) {
    SystemStateDto state = stateCache.get(monitorMqttPrefix);
    if (state != null && state.getDevices() != null) {
      Optional<DeviceDto> monitorOpt =
          state.getDevices().stream()
              .filter(d -> d.getDeviceType() == DeviceType.POWER_MONITOR)
              .findFirst();

      if (monitorOpt.isPresent()) {
        DeviceDto monitor = monitorOpt.get();
        Double currentTotal = getDeviceTotalEnergy(monitor.getId());

        if (currentTotal != null) {
          BlackoutSession session =
              new BlackoutSession(monitor.getId(), currentTotal, LocalDateTime.now());
          blackoutSessions.put(monitorMqttPrefix, session);

          logger.info(
              "Snapshotted POWER_MONITOR (ID: {}) for {}. Start Energy: {} Wh",
              monitor.getId(),
              monitorMqttPrefix,
              currentTotal);
        } else {
          logger.warn(
              "POWER_MONITOR found (ID: {}) but no energy data available.", monitor.getId());
        }
      } else {
        logger.warn(
            "No POWER_MONITOR found in system state for {} to track consumption.",
            monitorMqttPrefix);
      }
    }
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  private static class BlackoutSession {
    private Long powerMonitorId;
    private Double startEnergy;
    private LocalDateTime startTime;
  }
}
