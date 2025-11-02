package com.yh.sbps.device.dto;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DeviceDto {
  private Long id;
  private String name;
  private String mqttPrefix;
  private String deviceType;
  private Integer priority;
  private Integer wattage;
  private boolean preventDowntime;
  private Integer maxDowntimeMinutes;
  private Integer minUptimeMinutes;
  private String username;
}
