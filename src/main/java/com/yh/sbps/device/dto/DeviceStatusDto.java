package com.yh.sbps.device.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.yh.sbps.device.entity.DeviceStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeviceStatusDto {
  private boolean online;
  private JsonNode statusJson;

  public static DeviceStatusDto from(DeviceStatus entity, JsonNode statusJson) {
    if (entity == null) {
      return new DeviceStatusDto(false, null);
    }
    return new DeviceStatusDto(
        entity.getLastOnline() != null && entity.getLastOnline(), statusJson);
  }
}
