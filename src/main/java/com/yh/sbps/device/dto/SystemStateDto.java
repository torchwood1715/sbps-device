package com.yh.sbps.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SystemStateDto {
  @JsonProperty("system_settings")
  private SystemSettingsDto systemSettings;

  private List<DeviceDto> devices;
}
