package com.yh.sbps.device.dto;

import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class LoginRequestDto {
  private String email;
  private String password;
}
