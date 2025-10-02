package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
public class ShellyService {

  private static final Logger logger = LoggerFactory.getLogger(ShellyService.class);

  private final MqttPahoMessageHandler mqttOutbound;
  private final MqttPahoClientFactory mqttClientFactory;
  private final MessageChannel mqttInputChannel;
  private final ObjectMapper objectMapper;
  private final Map<String, JsonNode> lastStatusMap = new ConcurrentHashMap<>();
  private final Map<String, Boolean> onlineMap = new ConcurrentHashMap<>();
  private final Map<String, JsonNode> lastEventMap = new ConcurrentHashMap<>();
  private final Set<String> subscribedDevices = ConcurrentHashMap.newKeySet();

  public ShellyService(
      MqttPahoClientFactory mqttClientFactory,
      MessageChannel mqttInputChannel,
      ObjectMapper objectMapper) {
    this.mqttClientFactory = mqttClientFactory;
    this.mqttInputChannel = mqttInputChannel;
    this.objectMapper = objectMapper;
    this.mqttOutbound = new MqttPahoMessageHandler("shellyOutbound", mqttClientFactory);
    mqttOutbound.setAsync(false);
    mqttOutbound.setConverter(new DefaultPahoMessageConverter());
    mqttOutbound.afterPropertiesSet();
  }

  @ServiceActivator(inputChannel = "mqttInputChannel")
  public void handleMqttMessage(Message<?> message) {
    String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
    String payload = String.valueOf(message.getPayload());
    logger.info("MQTT IN: Topic={}, Payload={}", topic, payload);

    try {
      if (topic.endsWith("/online")) {
        onlineMap.put(topic, Boolean.parseBoolean(payload));
      } else if (topic.endsWith("/status/switch:0")) {
        String plugId = topic.substring(0, topic.indexOf("/status"));
        JsonNode json = objectMapper.readTree(payload);
        lastStatusMap.put(plugId, json);
      } else if (topic.endsWith("/events/rpc")) {
        String plugId = topic.substring(0, topic.indexOf("/events"));
        JsonNode json = objectMapper.readTree(payload);
        lastEventMap.put(plugId, json);
      }
    } catch (Exception e) {
      logger.error("Error parsing MQTT payload", e);
    }
  }

  public void sendCommand(String deviceId, boolean on) {
    try {
      var params = objectMapper.createObjectNode();
      params.put("id", 0);
      params.put("on", on);

      var payload = objectMapper.createObjectNode();
      payload.put("id", 1);
      payload.put("src", "device_service");
      payload.put("method", "Switch.Set");
      payload.set("params", params);

      String jsonPayload = objectMapper.writeValueAsString(payload);
      Message<String> mqttMsg =
          MessageBuilder.withPayload(jsonPayload)
              .setHeader(MqttHeaders.TOPIC, deviceId + "/rpc")
              .build();

      mqttOutbound.handleMessage(mqttMsg);
      logger.info("Sent toggle {} to [{}]", on, deviceId);

    } catch (Exception e) {
      logger.error("Error sending MQTT command", e);
    }
  }

  public JsonNode getLastStatus(String plugId) {
    return lastStatusMap.get(plugId);
  }

  public Boolean isOnline(String plugId) {
    return onlineMap.get(plugId + "/online");
  }

  public JsonNode getLastEvent(String plugId) {
    return lastEventMap.get(plugId);
  }

  public void subscribeForDevice(DeviceDto device) {
    if (device.getMqttPrefix() == null || device.getMqttPrefix().isEmpty()) {
      logger.warn("Device {} has no MQTT prefix, skipping subscription", device.getName());
      return;
    }

    String deviceKey = device.getMqttPrefix();
    if (subscribedDevices.contains(deviceKey)) {
      logger.debug("Device {} is already subscribed, skipping", device.getName());
      return;
    }

    try {
      String[] topics = {
        device.getMqttPrefix() + "/online",
        device.getMqttPrefix() + "/events/rpc",
        device.getMqttPrefix() + "/status/switch:0"
      };

      MqttPahoMessageDrivenChannelAdapter adapter =
          new MqttPahoMessageDrivenChannelAdapter(
              "shellyInbound_" + device.getId(), mqttClientFactory, topics);
      adapter.setCompletionTimeout(5000);
      adapter.setConverter(new DefaultPahoMessageConverter());
      adapter.setQos(1);
      adapter.setOutputChannel(mqttInputChannel);
      adapter.start();

      subscribedDevices.add(deviceKey);
      logger.info(
          "Subscribed to MQTT topics for device: {} ({})",
          device.getName(),
          device.getMqttPrefix());

    } catch (Exception e) {
      logger.error(
          "Failed to subscribe to MQTT topics for device: {} ({})",
          device.getName(),
          device.getMqttPrefix(),
          e);
    }
  }
}
