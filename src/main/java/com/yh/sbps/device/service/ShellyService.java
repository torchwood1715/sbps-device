package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShellyService {

    private static final Logger logger = LoggerFactory.getLogger(ShellyService.class);

    private final MqttPahoMessageHandler mqttOutbound;
    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> lastStatusMap = new ConcurrentHashMap<>();

    public ShellyService(MqttPahoClientFactory mqttClientFactory, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        // Outbound handler
        this.mqttOutbound = new MqttPahoMessageHandler("shellyOutbound", mqttClientFactory);
        this.mqttOutbound.setAsync(false);
        this.mqttOutbound.setConverter(new DefaultPahoMessageConverter());
        this.mqttOutbound.afterPropertiesSet();
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
            Message<String> mqttMsg = MessageBuilder.withPayload(jsonPayload)
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

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMqttMessage(Message<?> message) {
        String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        String payload = (String) message.getPayload();

        logger.debug("MQTT on [{}]: {}", topic, payload);

        if (topic != null && topic.endsWith("/status")) {
            String plugId = topic.substring(0, topic.indexOf("/status"));
            try {
                JsonNode json = objectMapper.readTree(payload);
                lastStatusMap.put(plugId, json);
                logger.info("Updated status for [{}]", plugId);
            } catch (Exception e) {
                logger.error("Error parsing status payload", e);
            }
        }
    }
}
