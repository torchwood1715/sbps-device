package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yh.sbps.device.dto.DeviceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class TasmotaMqttStrategy implements MqttProviderStrategy {

    private static final Logger logger = LoggerFactory.getLogger(TasmotaMqttStrategy.class);

    // Tasmota:
    // %prefix%/%topic%/%COMMAND% (%topic% - is mqttPrefix)
    // cmnd/mqtt_prefix/POWER (command)
    // tele/mqtt_prefix/LWT (online/offline)
    // tele/mqtt_prefix/STATE (periodical update)
    // tele/mqtt_prefix/SENSOR (sensors data)
    // stat/mqtt_prefix/RESULT (response from command)

    @Override
    public String[] getSubscriptionTopics(DeviceDto device) {
        String topic = device.getMqttPrefix();
        return new String[]{
                "tele/" + topic + "/LWT",
                "tele/" + topic + "/STATE",
                "tele/" + topic + "/SENSOR",
                "stat/" + topic + "/RESULT"
        };
    }

    @Override
    public String getMqttPrefixFromTopic(String topic) {
        String[] parts = topic.split("/");
        if (parts.length > 1) {
            return parts[1]; // "tele/PREFIX/LWT" -> "PREFIX"
        }
        return null;
    }

    @Override
    public Message<String> createToggleCommand(ObjectMapper objectMapper, String deviceMqttPrefix, boolean on) {
        String payload = on ? "ON" : "OFF";
        return MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, "cmnd/" + deviceMqttPrefix + "/POWER")
                .build();
    }

    @Override
    public boolean handleOnlineStatus(String topic, String payload, DeviceDto device, ShellyService service) {
        if (topic.endsWith("/LWT")) { // Last Will and Testament
            boolean online = "Online".equalsIgnoreCase(payload);
            service.handleOnlineStatusInternal(device, online);
            return true;
        }
        return false;
    }

    @Override
    public boolean handleDeviceStatus(String topic, JsonNode payload, DeviceDto device, ShellyService service) {
        try {
            JsonNode energyNode = null;
            if (topic.endsWith("/SENSOR") && payload.has("ENERGY")) {
                energyNode = payload.get("ENERGY");
            } else if (topic.endsWith("/STATE") && payload.has("ENERGY")) {
                energyNode = payload.get("ENERGY");
            }

            // Tasmota JSON: { "ENERGY": { "Power": 15.0, "Voltage": 230.0, ... } }
            // Or { "POWER": "ON" }
            ObjectNode shellyLikeStatus = new ObjectMapper().createObjectNode();
            boolean statusChanged = false;

            if (energyNode != null) {
                if (energyNode.has("Power")) {
                    shellyLikeStatus.put("apower", energyNode.get("Power").asDouble());
                    statusChanged = true;
                }
                if (energyNode.has("Voltage")) {
                    shellyLikeStatus.put("voltage", energyNode.get("Voltage").asDouble());
                    statusChanged = true;
                }
                if (energyNode.has("Current")) {
                    shellyLikeStatus.put("current", energyNode.get("Current").asDouble());
                    statusChanged = true;
                }
            }

            // Status ON/OFF can come with STATE or RESULT
            String powerState = null;
            if (topic.endsWith("/STATE") && payload.has("POWER")) {
                powerState = payload.get("POWER").asText();
            } else if (topic.endsWith("/RESULT") && payload.has("POWER")) {
                powerState = payload.get("POWER").asText();
            }

            if (powerState != null) {
                shellyLikeStatus.put("output", "ON".equalsIgnoreCase(powerState));
                statusChanged = true;
            }

            if (statusChanged) {
                service.handleDeviceStatusInternal(device, shellyLikeStatus);
                return true;
            }

        } catch (Exception e) {
            logger.error("Failed to parse Tasmota status for device {}", device.getMqttPrefix(), e);
        }
        return false;
    }

    @Override
    public boolean handleDeviceEvent(String topic, JsonNode payload, DeviceDto device, ShellyService service) {
        return false;
    }
}