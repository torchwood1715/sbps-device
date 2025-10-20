package com.yh.sbps.device.repository;

import com.yh.sbps.device.entity.DeviceStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceStatusRepository extends JpaRepository<DeviceStatus, Long> {

  Optional<DeviceStatus> findByDeviceId(Long deviceId);
}
