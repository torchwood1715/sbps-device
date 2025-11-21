package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.DeviceType;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.entity.SystemLog;
import com.yh.sbps.device.repository.SystemLogRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SystemLogService {

  private static final Logger logger = LoggerFactory.getLogger(SystemLogService.class);
  private final SystemLogRepository logRepository;
  private final SystemStateCache systemStateCache;
  private final DeviceRealtimeStateCache deviceRealtimeStateCache;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SystemLogService(
      SystemLogRepository logRepository,
      SystemStateCache systemStateCache,
      DeviceRealtimeStateCache deviceRealtimeStateCache) {
    this.logRepository = logRepository;
    this.systemStateCache = systemStateCache;
    this.deviceRealtimeStateCache = deviceRealtimeStateCache;
  }

  @Scheduled(fixedRate = 30000)
  public void logPeriodicState() {
    systemStateCache.getStateCache().keySet().forEach(prefix -> logSystemState(prefix, null));
  }

  public void logEvent(String mqttPrefix, String event) {
    logSystemState(mqttPrefix, event);
  }

  private void logSystemState(String mqttPrefix, String event) {
    try {
      Optional<SystemStateDto> stateOpt = systemStateCache.getState(mqttPrefix);
      if (stateOpt.isEmpty()) return;

      SystemStateDto state = stateOpt.get();

      // 1. Grid Status
      boolean gridOnline = systemStateCache.isGridAvailable(mqttPrefix);

      // 2. Total Load & Device Statuses
      double totalLoad = 0.0;
      ObjectNode devicesStatusNode = objectMapper.createObjectNode();

      if (state.getDevices() != null) {
        for (DeviceDto device : state.getDevices()) {
          Optional<DeviceStatus> rtStatusOpt = deviceRealtimeStateCache.get(device.getId());

          if (rtStatusOpt.isPresent()) {
            DeviceStatus rtStatus = rtStatusOpt.get();
            JsonNode jsonNode = parseJson(rtStatus.getLastStatusJson());

            if (device.getDeviceType() == DeviceType.POWER_MONITOR) {
              if (jsonNode != null && jsonNode.has("apower")) {
                totalLoad = jsonNode.get("apower").asDouble(0.0);
              }
            }

            if (device.getDeviceType() == DeviceType.SWITCHABLE_APPLIANCE) {
              boolean isOn = false;
              if (jsonNode != null && jsonNode.has("output")) {
                isOn = jsonNode.get("output").asBoolean();
              }

              devicesStatusNode.put(device.getName(), isOn ? "ON" : "OFF");
            }
          }
        }
      }

      SystemLog log =
          new SystemLog(mqttPrefix, gridOnline, totalLoad, devicesStatusNode.toString(), event);

      logRepository.save(log);
      logger.debug(
          "Logged system state for {}: Grid={}, Load={}W", mqttPrefix, gridOnline, totalLoad);

    } catch (Exception e) {
      logger.error("Failed to log system state for {}", mqttPrefix, e);
    }
  }

  private JsonNode parseJson(String json) {
    if (json == null) return null;
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }
}
