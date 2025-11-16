package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import org.springframework.messaging.Message;

public interface MqttProviderStrategy {

  String[] getSubscriptionTopics(DeviceDto device);

  String getMqttPrefixFromTopic(String topic);

  Message<String> createToggleCommand(
      ObjectMapper objectMapper, String deviceMqttPrefix, boolean on) throws Exception;

  boolean handleOnlineStatus(String topic, String payload, DeviceDto device, ShellyService service);

  boolean handleDeviceStatus(
      String topic, JsonNode payload, DeviceDto device, ShellyService service);

  boolean handleDeviceEvent(
      String topic, JsonNode payload, DeviceDto device, ShellyService service);
}
