package com.yh.sbps.device.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BalancerActionDto {
  private Long deviceId;
  private String deviceName;
  private String action;
}
