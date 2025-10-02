package com.yh.sbps.device.service;

import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.integration.ApiServiceClient;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeviceStartup {

  private static final Logger logger = LoggerFactory.getLogger(DeviceStartup.class);

  private final ApiServiceClient apiServiceClient;
  private final ShellyService shellyService;

  public DeviceStartup(ApiServiceClient apiServiceClient, ShellyService shellyService) {
    this.apiServiceClient = apiServiceClient;
    this.shellyService = shellyService;
  }

  @PostConstruct
  public void initializeDeviceSubscriptions() {
    logger.info("Starting device initialization and MQTT subscription process...");

    try {
      List<DeviceDto> devices = apiServiceClient.getAllDevices();

      if (devices.isEmpty()) {
        logger.warn("No devices found in API Service. MQTT subscriptions not created.");
        return;
      }

      logger.info(
          "Found {} devices in API Service. Starting MQTT subscriptions...", devices.size());

      int successCount = 0;
      for (DeviceDto device : devices) {
        try {
          shellyService.subscribeForDevice(device);
          successCount++;
          logger.info(
              "Subscribed to MQTT topics for device: {} ({})",
              device.getName(),
              device.getMqttPrefix());
        } catch (Exception e) {
          logger.error(
              "Failed to subscribe to device: {} ({})",
              device.getName(),
              device.getMqttPrefix(),
              e);
        }
      }

      logger.info(
          "Device initialization completed. Successfully subscribed to {}/{} devices.",
          successCount,
          devices.size());

    } catch (Exception e) {
      logger.error("Failed to initialize device subscriptions", e);
    }
  }
}
