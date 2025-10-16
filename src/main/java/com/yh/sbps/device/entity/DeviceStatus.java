package com.yh.sbps.device.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "device_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceStatus {

  @Id
  @Column(name = "device_id")
  private Long deviceId;

  @Column(name = "mqtt_prefix")
  private String mqttPrefix;

  @Lob
  @Column(name = "last_status_json")
  private String lastStatusJson;

  @Column(name = "last_online")
  private Boolean lastOnline;

  @Lob
  @Column(name = "last_event_json")
  private String lastEventJson;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  @PreUpdate
  public void updateTimestamp() {
    this.updatedAt = LocalDateTime.now();
  }
}
