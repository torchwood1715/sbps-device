package com.yh.sbps.device.dto;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SystemSettingsDto {
  private Long id;
  private Integer powerLimitWatts;
  private Integer powerOnMarginWatts;
  private Integer overloadCooldownSeconds;
  private Long userId;
}
