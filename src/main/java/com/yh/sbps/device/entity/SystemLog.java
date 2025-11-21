package com.yh.sbps.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "system_logs")
@Data
@NoArgsConstructor
public class SystemLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private LocalDateTime timestamp;

  private String mqttPrefix;

  private Boolean gridOnline;

  private Double totalLoadWatts;

  @Column(columnDefinition = "TEXT")
  private String deviceStatusesJson;

  private String decisionEvent;

  public SystemLog(
      String mqttPrefix,
      Boolean gridOnline,
      Double totalLoadWatts,
      String deviceStatusesJson,
      String decisionEvent) {
    this.timestamp = LocalDateTime.now();
    this.mqttPrefix = mqttPrefix;
    this.gridOnline = gridOnline;
    this.totalLoadWatts = totalLoadWatts;
    this.deviceStatusesJson = deviceStatusesJson;
    this.decisionEvent = decisionEvent;
  }
}
