package com.yh.sbps.device.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DeviceStatusUpdateDto {
  private Long deviceId;
  private String username;
  private Boolean isOnline;
  private JsonNode statusJson;
}
