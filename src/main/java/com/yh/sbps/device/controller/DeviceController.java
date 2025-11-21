package com.yh.sbps.device.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.dto.BlackoutStatsDto;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.DeviceStatusDto;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import com.yh.sbps.device.service.DeviceStatusService;
import com.yh.sbps.device.service.ShellyService;
import com.yh.sbps.device.service.SystemStateCache;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
public class DeviceController {

  private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

  private final ShellyService shellyService;
  private final DeviceStatusService deviceStatusService;
  private final SystemStateCache systemStateCache;

  public DeviceController(
      ShellyService shellyService,
      DeviceStatusService deviceStatusService,
      SystemStateCache systemStateCache) {
    this.shellyService = shellyService;
    this.deviceStatusService = deviceStatusService;
    this.systemStateCache = systemStateCache;
  }

  @PostMapping("/internal/subscribe")
  public ResponseEntity<Void> subscribeDevice(@RequestBody DeviceDto device) {
    try {
      shellyService.subscribeForDevice(device);
      logger.info("Subscribed to device topics via internal API call: {}", device.getName());
      systemStateCache.refreshState(device.getMqttPrefix());
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      logger.error("Failed to subscribe to device via internal API call: {}", device.getName(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/internal/unsubscribe")
  public ResponseEntity<Void> unsubscribeDevice(@RequestBody String mqttPrefix) {
    try {
      String prefix = mqttPrefix.replace("\"", "");
      shellyService.unsubscribeFromDevice(prefix);
      systemStateCache.removeDevice(prefix);
      logger.info("Unsubscribed from device via internal API call: {}", prefix);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      logger.error("Failed to unsubscribe from device via internal API call: {}", mqttPrefix, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/internal/refresh-state")
  public ResponseEntity<Void> refreshState(@RequestBody String mqttPrefix) {
    try {
      String prefix = mqttPrefix.replace("\"", "");
      systemStateCache.refreshState(prefix);
      logger.info("Refreshed system state via internal API call for: {}", prefix);
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      logger.error("Failed to refresh system state via internal API call: {}", mqttPrefix, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/plug/{deviceId}/toggle")
  public ResponseEntity<String> togglePlug(@PathVariable Long deviceId, @RequestParam boolean on) {
    try {
      Optional<DeviceDto> deviceOpt =
          systemStateCache.getStateCache().values().stream()
              .flatMap(
                  state ->
                      state.getDevices() != null ? state.getDevices().stream() : Stream.empty())
              .filter(device -> deviceId.equals(device.getId()))
              .findFirst();

      if (deviceOpt.isEmpty()) {
        logger.warn("Device with ID {} not found in cache", deviceId);
        Optional<String> mqttPrefixOpt = deviceStatusService.findMqttPrefixById(deviceId);
        if (mqttPrefixOpt.isEmpty()) {
          logger.error("Device with ID {} not found in cache or DB", deviceId);
          return ResponseEntity.notFound().build();
        }
        DeviceDto fallbackDevice = new DeviceDto();
        fallbackDevice.setId(deviceId);
        fallbackDevice.setName("Device " + deviceId);
        fallbackDevice.setMqttPrefix(mqttPrefixOpt.get());
        deviceOpt = Optional.of(fallbackDevice);
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

  @PostMapping("/internal/all-statuses")
  public ResponseEntity<Map<Long, DeviceStatusDto>> getAllStatusesByIds(
      @RequestBody List<Long> deviceIds) {
    try {
      Map<Long, DeviceStatusDto> statuses = deviceStatusService.getAllStatusesByIds(deviceIds);
      return ResponseEntity.ok(statuses);
    } catch (Exception e) {
      logger.error("Error fetching all device statuses", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @GetMapping("/internal/blackout-stats")
  public ResponseEntity<BlackoutStatsDto> getBlackoutStats(@RequestParam String mqttPrefix) {
    return ResponseEntity.ok(systemStateCache.getBlackoutStats(mqttPrefix));
  }
}
