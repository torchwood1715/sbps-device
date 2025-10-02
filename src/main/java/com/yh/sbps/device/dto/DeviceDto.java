package com.yh.sbps.device.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DeviceDto {
  private Long id;
  private String name;
  private String mqttPrefix;
  private String type;
  private Integer priority;

  public DeviceDto() {}

  public DeviceDto(Long id, String name, String mqttPrefix, String type, Integer priority) {
    this.id = id;
    this.name = name;
    this.mqttPrefix = mqttPrefix;
    this.type = type;
    this.priority = priority;
  }

  @Override
  public String toString() {
    return "DeviceDto{"
        + "id="
        + id
        + ", name='"
        + name
        + '\''
        + ", mqttPrefix='"
        + mqttPrefix
        + '\''
        + ", type='"
        + type
        + '\''
        + ", priority="
        + priority
        + '}';
  }
}
