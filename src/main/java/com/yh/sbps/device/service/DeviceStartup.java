package com.yh.sbps.device.service;

import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.DeviceType;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.integration.ApiServiceClient;
import com.yh.sbps.device.repository.DeviceStatusRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class DeviceStartup implements SmartLifecycle {

  private static final Logger logger = LoggerFactory.getLogger(DeviceStartup.class);

  private final ApiServiceClient apiServiceClient;
  private final ShellyService shellyService;
  private final SystemStateCache systemStateCache;
  private final DeviceStatusRepository deviceStatusRepository;
  private final DeviceRealtimeStateCache stateCache;
  private volatile boolean isRunning = false;

  public DeviceStartup(
      ApiServiceClient apiServiceClient,
      ShellyService shellyService,
      SystemStateCache systemStateCache,
      DeviceStatusRepository deviceStatusRepository,
      DeviceRealtimeStateCache stateCache) {
    this.apiServiceClient = apiServiceClient;
    this.shellyService = shellyService;
    this.systemStateCache = systemStateCache;
    this.deviceStatusRepository = deviceStatusRepository;
    this.stateCache = stateCache;
  }

  @Override
  public void start() {
    logger.info("Starting device initialization and MQTT subscription process (SmartLifecycle)...");
    this.isRunning = true;
    try {
      List<DeviceDto> devices = apiServiceClient.getAllDevices();

      if (devices.isEmpty()) {
        logger.warn("No devices found in API Service. MQTT subscriptions not created.");
        return;
      }

      logger.info("Initializing realtime device status cache...");
      try {
        List<DeviceStatus> allStatuses = deviceStatusRepository.findAll();
        stateCache.initCache(allStatuses);
        logger.info("Successfully initialized cache with {} device statuses.", allStatuses.size());
      } catch (Exception e) {
        logger.error("Failed to initialize realtime status cache!", e);
      }

      logger.info(
          "Found {} devices in API Service. Starting MQTT subscriptions...", devices.size());

      int successCount = 0;
      for (DeviceDto device : devices) {
        try {
          shellyService.subscribeForDevice(device);
          successCount++;
          if (DeviceType.POWER_MONITOR == device.getDeviceType()) {
            logger.info(
                "Found power monitor: {}. Initializing system state cache.", device.getName());
            systemStateCache.refreshState(device.getMqttPrefix());
          }
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

  @Override
  public void stop() {
    this.isRunning = false;
    logger.info("Stopping device subscriptions (SmartLifecycle)...");
    this.shellyService.unsubscribeForAllDevices();
  }

  @Override
  public boolean isRunning() {
    return this.isRunning;
  }
}
