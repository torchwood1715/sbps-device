package com.yh.sbps.device;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class DeviceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DeviceApplication.class, args);
  }
}
