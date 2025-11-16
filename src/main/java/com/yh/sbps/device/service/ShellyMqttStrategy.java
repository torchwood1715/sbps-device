package com.yh.sbps.device.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class ShellyMqttStrategy implements MqttProviderStrategy {

    @Override
    public String[] getSubscriptionTopics(DeviceDto device) {
        String prefix = device.getMqttPrefix();
        return new String[]{
                prefix + "/online",
                prefix + "/events/rpc",
                prefix + "/status/switch:0"
        };
    }

    @Override
    public String getMqttPrefixFromTopic(String topic) {
        if (topic.endsWith("/online")) {
            return topic.substring(0, topic.lastIndexOf("/online"));
        } else if (topic.contains("/status/")) {
            return topic.substring(0, topic.indexOf("/status/"));
        } else if (topic.contains("/events/")) {
            return topic.substring(0, topic.indexOf("/events/"));
        }
        return null;
    }

    @Override
    public Message<String> createToggleCommand(ObjectMapper objectMapper, String deviceMqttPrefix, boolean on) throws Exception {
        var params = objectMapper.createObjectNode();
        params.put("id", 0);
        params.put("on", on);

        var payload = objectMapper.createObjectNode();
        payload.put("id", 1);
        payload.put("src", "device_service");
        payload.put("method", "Switch.Set");
        payload.set("params", params);

        String jsonPayload = objectMapper.writeValueAsString(payload);
        return MessageBuilder.withPayload(jsonPayload)
                .setHeader(MqttHeaders.TOPIC, deviceMqttPrefix + "/rpc")
                .build();
    }

    @Override
    public boolean handleOnlineStatus(String topic, String payload, DeviceDto device, ShellyService service) {
        if (topic.endsWith("/online")) {
            boolean online = Boolean.parseBoolean(payload);
            service.handleOnlineStatusInternal(device, online);
            return true;
        }
        return false;
    }

    @Override
    public boolean handleDeviceStatus(String topic, JsonNode payload, DeviceDto device, ShellyService service) {
        if (topic.endsWith("/status/switch:0")) {
            service.handleDeviceStatusInternal(device, payload);
            return true;
        }

        // Обробка NotifyStatus з /events/rpc, яка містить 'apower'
        if (topic.endsWith("/events/rpc") &&
                payload.has("method") && "NotifyStatus".equals(payload.get("method").asText()) &&
                payload.has("params") && payload.get("params").has("switch:0") &&
                payload.get("params").get("switch:0").has("apower"))
        {
            JsonNode statusPayload = payload.get("params").get("switch:0");
            service.handleDeviceStatusInternal(device, statusPayload);
        }
        return false;
    }

    @Override
    public boolean handleDeviceEvent(String topic, JsonNode payload, DeviceDto device, ShellyService service) {
        if (topic.endsWith("/events/rpc")) {
            service.handleDeviceEventInternal(device, payload);
            return true;
        }
        return false;
    }
}