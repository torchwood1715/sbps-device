package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DeviceRealtimeStateCache {

  private static final Logger logger = LoggerFactory.getLogger(DeviceRealtimeStateCache.class);

  // key deviceId
  private final Map<Long, DeviceStatus> cache = new ConcurrentHashMap<>();

  public void initCache(List<DeviceStatus> allStatuses) {
    allStatuses.forEach(status -> cache.put(status.getDeviceId(), status));
  }

  public Optional<DeviceStatus> get(Long deviceId) {
    return Optional.ofNullable(cache.get(deviceId));
  }

  private DeviceStatus getOrCreate(Long deviceId) {
    return cache.computeIfAbsent(
        deviceId,
        id -> {
          DeviceStatus status = new DeviceStatus();
          status.setDeviceId(id);
          return status;
        });
  }

  public DeviceStatus updateOnline(Long deviceId, boolean online, String mqttPrefix) {
    DeviceStatus status = getOrCreate(deviceId);
    status.setLastOnline(online);
    status.setMqttPrefix(mqttPrefix);
    return status;
  }

  public DeviceStatus updateStatus(Long deviceId, JsonNode statusJson, String mqttPrefix) {
    DeviceStatus status = getOrCreate(deviceId);
    try {
      status.setLastStatusJson(statusJson.toString());
    } catch (Exception e) {
      logger.error("Failed to update status for device id: {}", deviceId, e);
    }
    status.setMqttPrefix(mqttPrefix);
    return status;
  }

  public DeviceStatus updateEvent(Long deviceId, JsonNode eventJson, String mqttPrefix) {
    DeviceStatus status = getOrCreate(deviceId);
    try {
      status.setLastEventJson(eventJson.toString());
    } catch (Exception e) {
      logger.error("Failed to update event for device id: {}", deviceId, e);
    }
    status.setMqttPrefix(mqttPrefix);
    return status;
  }

  public DeviceStatus updateControlState(Long deviceId, DeviceControlState state) {
    DeviceStatus status = getOrCreate(deviceId);
    status.setControlState(state);
    if (state == DeviceControlState.DISABLED_BY_BALANCER) {
      status.setBalancerDisabledAt(LocalDateTime.now());
    } else {
      status.setBalancerDisabledAt(null);
    }
    return status;
  }
}
