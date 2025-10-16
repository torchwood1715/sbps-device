package com.yh.sbps.device.config;

import com.yh.sbps.device.service.BalancingService;
import com.yh.sbps.device.service.ShellyService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/** Configuration class to handle circular dependency between BalancingService and ShellyService. */
@Configuration
public class ServiceConfiguration {

  private final BalancingService balancingService;
  private final ShellyService shellyService;

  public ServiceConfiguration(BalancingService balancingService, ShellyService shellyService) {
    this.balancingService = balancingService;
    this.shellyService = shellyService;
  }

  @PostConstruct
  public void init() {
    // Wire the circular dependency after both beans are constructed
    balancingService.setShellyService(shellyService);
    shellyService.setBalancingService(balancingService);
  }
}
