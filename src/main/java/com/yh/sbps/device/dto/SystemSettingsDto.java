package com.yh.sbps.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SystemSettingsDto {
  private Long id;

  @JsonProperty("power_limit_watts")
  private Integer powerLimitWatts;

  @JsonProperty("user_id")
  private Long userId;
}
