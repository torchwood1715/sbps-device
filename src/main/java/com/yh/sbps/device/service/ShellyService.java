package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.DeviceProvider;
import com.yh.sbps.device.dto.DeviceStatusUpdateDto;
import com.yh.sbps.device.dto.DeviceType;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Setter;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ShellyService {

  private static final Logger logger = LoggerFactory.getLogger(ShellyService.class);

  private final MqttPahoMessageHandler mqttOutbound;
  private final MqttPahoClientFactory mqttClientFactory;
  private final MessageChannel mqttInputChannel;
  private final ObjectMapper objectMapper;
  private final DeviceStatusService deviceStatusService;
  private final ApiServiceClient apiServiceClient;
  private final Map<String, MqttPahoMessageDrivenChannelAdapter> subscribedAdapters =
      new ConcurrentHashMap<>();
  private final Map<String, DeviceDto> deviceCache = new ConcurrentHashMap<>();
  private final DeviceRealtimeStateCache stateCache;
  private final Map<DeviceProvider, MqttProviderStrategy> strategies;
  @Setter private BalancingService balancingService; // Lazy injection to avoid circular dependency
  @Setter private SystemStateCache systemStateCache; // Lazy injection to avoid circular dependency

  public ShellyService(
      MqttPahoClientFactory mqttClientFactory,
      MessageChannel mqttInputChannel,
      ObjectMapper objectMapper,
      DeviceStatusService deviceStatusService,
      ApiServiceClient apiServiceClient,
      DeviceRealtimeStateCache stateCache,
      ShellyMqttStrategy shellyStrategy,
      TasmotaMqttStrategy tasmotaStrategy) {
    this.mqttClientFactory = mqttClientFactory;
    this.mqttInputChannel = mqttInputChannel;
    this.objectMapper = objectMapper;
    this.deviceStatusService = deviceStatusService;
    this.apiServiceClient = apiServiceClient;
    this.mqttOutbound = new MqttPahoMessageHandler("shellyOutbound", mqttClientFactory);
    this.stateCache = stateCache;
    this.strategies =
        Map.of(
            DeviceProvider.SHELLY, shellyStrategy,
            DeviceProvider.TASMOTA, tasmotaStrategy);
    mqttOutbound.setAsync(false);
    mqttOutbound.setConverter(new DefaultPahoMessageConverter());
    mqttOutbound.afterPropertiesSet();
  }

  @ServiceActivator(inputChannel = "mqttInputChannel")
  public void handleMqttMessage(Message<?> message) {
    String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
    String payload = String.valueOf(message.getPayload());
    logger.debug("MQTT IN: Topic={}, Payload={}", topic, payload);
    try {
      DeviceDto device = findDeviceByTopic(topic);
      if (device == null) {
        logger.warn("No device found for topic: {}", topic);
        return;
      }
      MqttProviderStrategy strategy = strategies.get(device.getProvider());
      if (strategy == null) {
        logger.error("No MQTT strategy found for provider: {}", device.getProvider());
        return;
      }
      if (strategy.handleOnlineStatus(topic, payload, device, this)) {
        return;
      }
      JsonNode jsonPayload = objectMapper.readTree(payload);
      strategy.handleDeviceStatus(topic, jsonPayload, device, this);
      strategy.handleDeviceEvent(topic, jsonPayload, device, this);
    } catch (Exception e) {
      logger.error("Error parsing MQTT payload for topic {}: {}", topic, e.getMessage());
    }
  }

  public void handleOnlineStatusInternal(DeviceDto device, boolean online) {
    stateCache.updateOnline(device.getId(), online, device.getMqttPrefix());
    deviceStatusService.updateOnline(device.getId(), online, device.getMqttPrefix());
    if (device.getUsername() != null) {
      apiServiceClient.notifyApiOfDeviceUpdate(
          new DeviceStatusUpdateDto(device.getId(), device.getUsername(), online, null));
    }

    if (device.getDeviceType() == DeviceType.GRID_MONITOR) {
      String monitorPrefix = findMonitorPrefixForDevice(device);
      logger.info(
          "GRID_MONITOR '{}' is now {}. Updating grid status for monitor {}.",
          device.getName(),
          online ? "ONLINE" : "OFFLINE",
          monitorPrefix);
      systemStateCache.updateGridStatus(monitorPrefix, online);
    }
  }

  public void handleDeviceStatusInternal(DeviceDto device, JsonNode json) {
    stateCache.updateStatus(device.getId(), json, device.getMqttPrefix());
    String monitorPrefix = findMonitorPrefixForDevice(device);

    if (device.getDeviceType() == DeviceType.GRID_MONITOR && json.has("voltage")) {
      double voltage = json.get("voltage").asDouble(0.0);
      boolean isGridAvailable = voltage > 100.0;

      boolean oldStatus = systemStateCache.isGridAvailable(monitorPrefix);

      if (isGridAvailable != oldStatus) {
        logger.warn(
            "GRID STATUS CHANGE! Monitor: {}. Voltage: {}. Grid available: {}",
            monitorPrefix,
            voltage,
            isGridAvailable);
        systemStateCache.updateGridStatus(monitorPrefix, isGridAvailable);
      }
    }

    if (device.getDeviceType() == DeviceType.POWER_MONITOR) {
      if (balancingService != null) {
        balancingService.balancePower(device.getMqttPrefix(), json);
      } else {
        logger.warn("BalancingService not initialized. Cannot perform power balancing.");
      }
    }

    performPostProcessing(device.getMqttPrefix(), json, device);
  }

  public void handleDeviceEventInternal(DeviceDto device, JsonNode json) {
    deviceStatusService.updateEvent(device.getId(), json, device.getMqttPrefix());
  }

  private DeviceDto findDeviceByTopic(String topic) {
    String tasmotaPrefix = getTasmotaPrefixFromTopic(topic);
    if (tasmotaPrefix != null) {
      DeviceDto device = getDeviceByMqttPrefix(tasmotaPrefix);
      if (device != null && device.getProvider() == DeviceProvider.TASMOTA) {
        return device;
      }
    }

    for (MqttProviderStrategy strategy : strategies.values()) {
      if (strategy instanceof ShellyMqttStrategy) {
        String prefix = strategy.getMqttPrefixFromTopic(topic);
        if (prefix != null) {
          DeviceDto device = getDeviceByMqttPrefix(prefix);
          if (device != null) {
            return device;
          }
        }
      }
    }
    return null;
  }

  private String getTasmotaPrefixFromTopic(String topic) {
    String[] parts = topic.split("/");
    if (parts.length > 1
        && (parts[0].equals("tele") || parts[0].equals("stat") || parts[0].equals("cmnd"))) {
      return parts[1]; // "tele/PREFIX/LWT" -> "PREFIX"
    }
    return null;
  }

  private String findMonitorPrefixForDevice(DeviceDto device) {
    if (device.getDeviceType() == DeviceType.POWER_MONITOR
        || device.getDeviceType() == DeviceType.GRID_MONITOR) {
      return device.getMqttPrefix();
    }

    String monitorPrefix = systemStateCache.getDeviceToMonitorMap().get(device.getMqttPrefix());

    if (monitorPrefix != null) {
      return monitorPrefix;
    }

    logger.warn(
        "No monitor prefix in deviceToMonitorMap for {}. Using device's own prefix as fallback.",
        device.getMqttPrefix());
    return device.getMqttPrefix();
  }

  @Async
  public void performPostProcessing(String mqttPrefix, JsonNode json, DeviceDto device) {
    if (device == null) {
      // Re-fetch if it wasn't passed in
      device = getDeviceByMqttPrefix(mqttPrefix);
    }

    if (device != null) {
      deviceStatusService.updateStatus(device.getId(), json, mqttPrefix);
      if (device.getUsername() != null) {
        apiServiceClient.notifyApiOfDeviceUpdate(
            new DeviceStatusUpdateDto(device.getId(), device.getUsername(), null, json));
      }
    }
  }

  private DeviceDto getDeviceByMqttPrefix(String mqttPrefix) {
    DeviceDto device = deviceCache.get(mqttPrefix);
    if (device != null) {
      return device;
    }

    logger.warn("Device with prefix {} not found in cache. Forcing API fetch.", mqttPrefix);
    try {
      List<DeviceDto> devices = apiServiceClient.getAllDevices();
      for (DeviceDto dev : devices) {
        refreshDeviceCache(dev);
        if (mqttPrefix.equals(dev.getMqttPrefix())) {
          device = dev;
        }
      }
    } catch (Exception e) {
      logger.error("Error finding device by MQTT prefix: {}", mqttPrefix, e);
    }

    if (device == null) {
      logger.warn("No device found for MQTT prefix: {}", mqttPrefix);
    }
    return device;
  }

  public void sendCommand(String deviceMqttPrefix, boolean on) {
    try {
      DeviceDto device = getDeviceByMqttPrefix(deviceMqttPrefix);
      if (device == null) {
        logger.error("Cannot send command, device not found for prefix: {}", deviceMqttPrefix);
        return;
      }
      MqttProviderStrategy strategy = strategies.get(device.getProvider());
      if (strategy == null) {
        logger.error("Cannot send command, no strategy for provider: {}", device.getProvider());
        return;
      }

      Message<String> mqttMsg = strategy.createToggleCommand(objectMapper, deviceMqttPrefix, on);

      mqttOutbound.handleMessage(mqttMsg);
      logger.info("Sent toggle {} to device {} ({})", on, device.getName(), device.getMqttPrefix());

    } catch (Exception e) {
      logger.error("Error sending MQTT command", e);
    }
  }

  public void subscribeForDevice(DeviceDto device) {
    if (device.getMqttPrefix() == null || device.getMqttPrefix().isEmpty()) {
      logger.warn("Device {} has no MQTT prefix, skipping subscription", device.getName());
      return;
    }

    MqttProviderStrategy strategy = strategies.get(device.getProvider());
    if (strategy == null) {
      logger.error(
          "Cannot subscribe, no strategy for provider: {} (Device: {})",
          device.getProvider(),
          device.getName());
      return;
    }

    String deviceKey = device.getMqttPrefix();
    if (subscribedAdapters.containsKey(deviceKey)) {
      logger.debug("Device {} is already subscribed, skipping", device.getName());
      return;
    }

    try {
      String[] topics = strategy.getSubscriptionTopics(device);

      MqttPahoMessageDrivenChannelAdapter adapter =
          new MqttPahoMessageDrivenChannelAdapter(
              "mqttInbound_" + device.getId(), mqttClientFactory, topics);
      adapter.setCompletionTimeout(5000);
      adapter.setConverter(new DefaultPahoMessageConverter());
      adapter.setQos(1);
      adapter.setOutputChannel(mqttInputChannel);
      adapter.start();

      subscribedAdapters.put(deviceKey, adapter);
      refreshDeviceCache(device);
      logger.info(
          "Subscribed to {} topics for device: {} ({})",
          topics.length,
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

  public void unsubscribeFromDevice(String mqttPrefix) {
    if (mqttPrefix == null || mqttPrefix.isEmpty()) {
      return;
    }

    MqttPahoMessageDrivenChannelAdapter adapter = subscribedAdapters.remove(mqttPrefix);
    if (adapter != null) {
      adapter.stop();
      logger.info("Unsubscribed from MQTT topics for prefix: {}", mqttPrefix);

      if (balancingService != null) {
        balancingService.clearOverloadCooldown(mqttPrefix);
      }
    } else {
      logger.warn("No active subscription found for prefix to unsubscribe: {}", mqttPrefix);
    }

    deviceCache.remove(mqttPrefix);
  }

  public void refreshDeviceCache(DeviceDto device) {
    if (device == null || device.getMqttPrefix() == null) {
      return;
    }
    deviceCache.put(device.getMqttPrefix(), device);
    logger.debug("Refreshed device cache for: {}", device.getName());
  }

  public void unsubscribeForAllDevices() {
    subscribedAdapters.forEach((prefix, adapter) -> unsubscribeFromDevice(prefix));
  }
}
