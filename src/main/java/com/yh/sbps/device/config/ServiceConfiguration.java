package com.yh.sbps.device.config;

import com.yh.sbps.device.service.BalancingService;
import com.yh.sbps.device.service.ShellyService;
import com.yh.sbps.device.service.SystemStateCache;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/** Configuration class to handle circular dependency between BalancingService and ShellyService. */
@Configuration
public class ServiceConfiguration {

  private final BalancingService balancingService;
  private final ShellyService shellyService;
  private final SystemStateCache systemStateCache;

  public ServiceConfiguration(
      BalancingService balancingService,
      ShellyService shellyService,
      SystemStateCache systemStateCache) {
    this.balancingService = balancingService;
    this.shellyService = shellyService;
    this.systemStateCache = systemStateCache;
  }

  @PostConstruct
  public void init() {
    // Wire the circular dependencies

    // BalancingService <-> ShellyService
    balancingService.setShellyService(shellyService);
    shellyService.setBalancingService(balancingService);

    // ShellyService <-> SystemStateCache
    shellyService.setSystemStateCache(systemStateCache);
    systemStateCache.setShellyService(shellyService);
  }
}
