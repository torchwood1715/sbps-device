package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.repository.DeviceStatusRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DeviceStatusService {

  private static final Logger logger = LoggerFactory.getLogger(DeviceStatusService.class);

  private final DeviceStatusRepository deviceStatusRepository;
  private final ObjectMapper objectMapper;

  public DeviceStatusService(
      DeviceStatusRepository deviceStatusRepository, ObjectMapper objectMapper) {
    this.deviceStatusRepository = deviceStatusRepository;
    this.objectMapper = objectMapper;
  }

  public void updateStatus(Long deviceId, JsonNode status, String mqttPrefix) {
    try {
      String statusJson = objectMapper.writeValueAsString(status);
      DeviceStatus deviceStatus =
          deviceStatusRepository.findByDeviceId(deviceId).orElse(new DeviceStatus());

      deviceStatus.setDeviceId(deviceId);
      deviceStatus.setMqttPrefix(mqttPrefix);
      deviceStatus.setLastStatusJson(statusJson);

      deviceStatusRepository.save(deviceStatus);
      logger.info("Updated status for device {}: {}", deviceId, statusJson);
    } catch (Exception e) {
      logger.error("Error updating status for device {}", deviceId, e);
    }
  }

  public void updateOnline(Long deviceId, boolean online, String mqttPrefix) {
    try {
      DeviceStatus deviceStatus =
          deviceStatusRepository.findByDeviceId(deviceId).orElse(new DeviceStatus());

      deviceStatus.setDeviceId(deviceId);
      deviceStatus.setMqttPrefix(mqttPrefix);
      deviceStatus.setLastOnline(online);

      deviceStatusRepository.save(deviceStatus);
      logger.info("Updated online status for device {}: {}", deviceId, online);
    } catch (Exception e) {
      logger.error("Error updating online status for device {}", deviceId, e);
    }
  }

  public void updateEvent(Long deviceId, JsonNode event, String mqttPrefix) {
    try {
      String eventJson = objectMapper.writeValueAsString(event);
      DeviceStatus deviceStatus =
          deviceStatusRepository.findByDeviceId(deviceId).orElse(new DeviceStatus());

      deviceStatus.setDeviceId(deviceId);
      deviceStatus.setMqttPrefix(mqttPrefix);
      deviceStatus.setLastEventJson(eventJson);

      deviceStatusRepository.save(deviceStatus);
      logger.info("Updated event for device {}: {}", deviceId, eventJson);
    } catch (Exception e) {
      logger.error("Error updating event for device {}", deviceId, e);
    }
  }

  public Optional<DeviceStatus> findByDeviceId(Long deviceId) {
    return deviceStatusRepository.findByDeviceId(deviceId);
  }

  public Optional<DeviceStatus> findByMqttPrefix(String mqttPrefix) {
    return deviceStatusRepository.findByMqttPrefix(mqttPrefix);
  }

  public JsonNode getStatusAsJsonNode(Long deviceId) {
    return findByDeviceId(deviceId)
        .map(DeviceStatus::getLastStatusJson)
        .map(this::parseJson)
        .orElse(null);
  }

  public JsonNode getEventAsJsonNode(Long deviceId) {
    return findByDeviceId(deviceId)
        .map(DeviceStatus::getLastEventJson)
        .map(this::parseJson)
        .orElse(null);
  }

  public Boolean getOnlineStatus(Long deviceId) {
    return findByDeviceId(deviceId).map(DeviceStatus::getLastOnline).orElse(null);
  }

  private JsonNode parseJson(String json) {
    if (json == null) return null;
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      logger.error("Error parsing JSON: {}", json, e);
      return null;
    }
  }
}
