package com.yh.sbps.device.dto;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SystemSettingsDto {
  private Integer powerLimitWatts;
  private Integer powerOnMarginWatts;
  private Integer overloadCooldownSeconds;
}
