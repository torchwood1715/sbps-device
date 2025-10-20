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
  private String type;
  private Integer priority;
}
