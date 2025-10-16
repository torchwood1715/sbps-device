package com.yh.sbps.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DeviceDto {
  private Long id;
  private String name;

  @JsonProperty("mqtt_prefix")
  private String mqttPrefix;

  private String type;
  private Integer priority;
}
