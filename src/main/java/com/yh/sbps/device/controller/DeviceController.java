package com.yh.sbps.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import com.yh.sbps.device.integration.ApiServiceClient;
import com.yh.sbps.device.service.DeviceStatusService;
import com.yh.sbps.device.service.ShellyService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
public class DeviceController {

  private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

  private final ShellyService shellyService;
  private final ApiServiceClient apiServiceClient;
  private final DeviceStatusService deviceStatusService;

  public DeviceController(
      ShellyService shellyService,
      ApiServiceClient apiServiceClient,
      DeviceStatusService deviceStatusService) {
    this.shellyService = shellyService;
    this.apiServiceClient = apiServiceClient;
    this.deviceStatusService = deviceStatusService;
  }

  @PostMapping("/internal/subscribe")
  public ResponseEntity<Void> subscribeDevice(@RequestBody DeviceDto device) {
    try {
      shellyService.subscribeForDevice(device);
      logger.info("Subscribed to device topics via internal API call: {}", device.getName());
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      logger.error("Failed to subscribe to device via internal API call: {}", device.getName(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/plug/{deviceId}/toggle")
  public ResponseEntity<String> togglePlug(@PathVariable Long deviceId, @RequestParam boolean on) {
    try {
      Optional<DeviceDto> deviceOpt = apiServiceClient.getDeviceById(deviceId);

      if (deviceOpt.isEmpty()) {
        logger.warn("Device with ID {} not found", deviceId);
        return ResponseEntity.notFound().build();
      }

      DeviceDto device = deviceOpt.get();
      if (device.getMqttPrefix() == null || device.getMqttPrefix().isEmpty()) {
        logger.error("Device {} has no MQTT prefix", device.getName());
        return ResponseEntity.badRequest().body("Device has no MQTT prefix configured");
      }

      // Send command to a device
      shellyService.sendCommand(device.getMqttPrefix(), on);

      // Update control state based on user action
      DeviceControlState newState =
          on ? DeviceControlState.ENABLED : DeviceControlState.DISABLED_BY_USER;
      deviceStatusService.updateControlState(deviceId, newState);

      logger.info(
          "User toggled device {} ({}) to {} with control state: {}",
          device.getName(),
          device.getMqttPrefix(),
          on ? "ON" : "OFF",
          newState);

      return ResponseEntity.ok(
          "Device [" + device.getName() + "] toggled " + (on ? "ON" : "OFF") + " by user");

    } catch (Exception e) {
      logger.error("Error user-toggling device {}", deviceId, e);
      return ResponseEntity.internalServerError().body("Error toggling device");
    }
  }

  @GetMapping("/plug/{deviceId}/status")
  public ResponseEntity<JsonNode> getStatus(@PathVariable Long deviceId) {
    JsonNode status = deviceStatusService.getStatusAsJsonNode(deviceId);
    if (status == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(status);
  }

  @GetMapping("/plug/{deviceId}/online")
  public ResponseEntity<Boolean> getOnline(@PathVariable Long deviceId) {
    Boolean online = deviceStatusService.getOnlineStatus(deviceId);
    if (online == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(online);
  }

  @GetMapping("/plug/{deviceId}/events")
  public ResponseEntity<JsonNode> getEvents(@PathVariable Long deviceId) {
    JsonNode event = deviceStatusService.getEventAsJsonNode(deviceId);
    if (event == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(event);
  }
}
