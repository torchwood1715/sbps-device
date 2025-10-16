package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.util.List;
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
  private static final String DEVICE_TYPE_POWER_MONITOR = "POWER_MONITOR";

  private final MqttPahoMessageHandler mqttOutbound;
  private final MqttPahoClientFactory mqttClientFactory;
  private final MessageChannel mqttInputChannel;
  private final ObjectMapper objectMapper;
  private final DeviceStatusService deviceStatusService;
  private final ApiServiceClient apiServiceClient;
  private final Set<String> subscribedDevices = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> mqttPrefixToDeviceIdMap = new ConcurrentHashMap<>();
  private final Map<String, DeviceDto> deviceCache = new ConcurrentHashMap<>();
  private BalancingService balancingService; // Lazy injection to avoid circular dependency

  public ShellyService(
      MqttPahoClientFactory mqttClientFactory,
      MessageChannel mqttInputChannel,
      ObjectMapper objectMapper,
      DeviceStatusService deviceStatusService,
      ApiServiceClient apiServiceClient) {
    this.mqttClientFactory = mqttClientFactory;
    this.mqttInputChannel = mqttInputChannel;
    this.objectMapper = objectMapper;
    this.deviceStatusService = deviceStatusService;
    this.apiServiceClient = apiServiceClient;
    this.mqttOutbound = new MqttPahoMessageHandler("shellyOutbound", mqttClientFactory);
    mqttOutbound.setAsync(false);
    mqttOutbound.setConverter(new DefaultPahoMessageConverter());
    mqttOutbound.afterPropertiesSet();
  }

  /**
   * Setter for BalancingService to avoid circular dependency. Spring will inject this after
   * construction.
   */
  public void setBalancingService(BalancingService balancingService) {
    this.balancingService = balancingService;
  }

  @ServiceActivator(inputChannel = "mqttInputChannel")
  public void handleMqttMessage(Message<?> message) {
    String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
    String payload = String.valueOf(message.getPayload());
    logger.info("MQTT IN: Topic={}, Payload={}", topic, payload);

    try {
      if (topic.endsWith("/online")) {
        String mqttPrefix = topic.substring(0, topic.lastIndexOf("/online"));
        boolean online = Boolean.parseBoolean(payload);
        Long deviceId = getDeviceIdByMqttPrefix(mqttPrefix);
        if (deviceId != null) {
          deviceStatusService.updateOnline(deviceId, online, mqttPrefix);
        }
      } else if (topic.endsWith("/status/switch:0")) {
        String mqttPrefix = topic.substring(0, topic.indexOf("/status"));
        JsonNode json = objectMapper.readTree(payload);
        Long deviceId = getDeviceIdByMqttPrefix(mqttPrefix);
        if (deviceId != null) {
          deviceStatusService.updateStatus(deviceId, json, mqttPrefix);

          // Check if this is a POWER_MONITOR device and trigger balancing logic
          DeviceDto device = getDeviceByMqttPrefix(mqttPrefix);
          if (device != null && DEVICE_TYPE_POWER_MONITOR.equals(device.getType())) {
            logger.info(
                "Power monitor device '{}' reported status. Triggering balancing logic.",
                device.getName());
            if (balancingService != null) {
              balancingService.balancePower(mqttPrefix, json);
            } else {
              logger.warn("BalancingService not initialized. Cannot perform power balancing.");
            }
          }
        }
      } else if (topic.endsWith("/events/rpc")) {
        String mqttPrefix = topic.substring(0, topic.indexOf("/events"));
        JsonNode json = objectMapper.readTree(payload);
        Long deviceId = getDeviceIdByMqttPrefix(mqttPrefix);
        if (deviceId != null) {
          deviceStatusService.updateEvent(deviceId, json, mqttPrefix);
        }
      }
    } catch (Exception e) {
      logger.error("Error parsing MQTT payload", e);
    }
  }

  private Long getDeviceIdByMqttPrefix(String mqttPrefix) {
    // Check cache first
    Long deviceId = mqttPrefixToDeviceIdMap.get(mqttPrefix);
    if (deviceId != null) {
      return deviceId;
    }

    // If not in the cache, search through all devices
    try {
      List<DeviceDto> devices = apiServiceClient.getAllDevices();
      for (DeviceDto device : devices) {
        if (mqttPrefix.equals(device.getMqttPrefix())) {
          mqttPrefixToDeviceIdMap.put(mqttPrefix, device.getId());
          deviceCache.put(mqttPrefix, device); // Also cache the full device object
          return device.getId();
        }
      }
    } catch (Exception e) {
      logger.error("Error finding device by MQTT prefix: {}", mqttPrefix, e);
    }

    logger.warn("No device found for MQTT prefix: {}", mqttPrefix);
    return null;
  }

  /**
   * Gets the full DeviceDto by MQTT prefix. Uses cache for efficiency.
   *
   * @param mqttPrefix The MQTT prefix
   * @return DeviceDto or null if not found
   */
  private DeviceDto getDeviceByMqttPrefix(String mqttPrefix) {
    // Check cache first
    DeviceDto device = deviceCache.get(mqttPrefix);
    if (device != null) {
      return device;
    }

    // If not in cache, search through all devices
    try {
      List<DeviceDto> devices = apiServiceClient.getAllDevices();
      for (DeviceDto dev : devices) {
        if (mqttPrefix.equals(dev.getMqttPrefix())) {
          deviceCache.put(mqttPrefix, dev);
          mqttPrefixToDeviceIdMap.put(mqttPrefix, dev.getId());
          return dev;
        }
      }
    } catch (Exception e) {
      logger.error("Error finding device by MQTT prefix: {}", mqttPrefix, e);
    }

    logger.warn("No device found for MQTT prefix: {}", mqttPrefix);
    return null;
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
