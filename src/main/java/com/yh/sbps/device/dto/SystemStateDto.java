package com.yh.sbps.device.dto;

import java.util.List;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SystemStateDto {
  private SystemSettingsDto systemSettings;
  private List<DeviceDto> devices;
  private boolean isGridPowerAvailable;
}
