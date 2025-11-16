package com.yh.sbps.device.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.*;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BalancingService Unit Tests")
class BalancingServiceTest {

  @Mock private SystemStateCache systemStateCache;

  @Mock private DeviceRealtimeStateCache stateCache;

  @Mock private DeviceStatusService deviceStatusService;

  @Mock private ShellyService shellyService;

  @Mock private ApiServiceClient apiServiceClient;

  private BalancingService balancingService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    balancingService =
        new BalancingService(stateCache, deviceStatusService, apiServiceClient, systemStateCache);
    balancingService.setShellyService(shellyService);
  }

  @Test
  @DisplayName("Scenario 1: Normal state (No overload)")
  void testBalancePower_whenNoOverload_thenNoAction() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 800.0}");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.emptyList());

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(stateCache, never()).updateControlState(anyLong(), any(DeviceControlState.class));
  }

  @Test
  @DisplayName("Scenario 2: Simple overload")
  void testOverload_TurnsOffLowestPriorityDevice() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1200.0}");

    DeviceDto device1 =
        createDeviceDto(1L, "Device A", "mqtt_device_A", 1, 300, false, 0, 0, "user");
    DeviceDto device2 =
        createDeviceDto(2L, "Device B", "mqtt_device_B", 5, 300, false, 0, 0, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device statuses - both devices are online and ON with actual power
    mockDeviceOnlineAndOn(device1);
    mockDeviceOnlineAndOn(device2);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device B has lower priority (5 > 1), so it should be turned off
    verify(shellyService, times(1)).sendCommand("mqtt_device_B", false);
    verify(shellyService, never()).sendCommand("mqtt_device_A", false);
    verify(stateCache, times(1)).updateControlState(2L, DeviceControlState.DISABLED_BY_BALANCER);
    verify(apiServiceClient, times(1))
        .notifyBalancerAction(
            argThat(
                dto -> dto.getDeviceId() == 2L && "DISABLED_BY_BALANCER".equals(dto.getAction())));
  }

  @Test
  @DisplayName("Scenario 3: Multiple overload")
  void testOverload_whenMultipleDevices_thenTurnsOffInPriorityOrder() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1500.0}");

    DeviceDto deviceA =
        createDeviceDto(1L, "Device A", "mqtt_device_A", 1, 100, true, 60, 20, "user");
    DeviceDto deviceB =
        createDeviceDto(2L, "Device B", "mqtt_device_B", 5, 200, true, 60, 20, "user");
    DeviceDto deviceC =
        createDeviceDto(3L, "Device C", "mqtt_device_C", 10, 300, true, 60, 20, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(deviceA, deviceB, deviceC));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device statuses
    mockDeviceOnlineAndOn(deviceA);
    mockDeviceOnlineAndOn(deviceB);
    mockDeviceOnlineAndOn(deviceC);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Should turn off C (prio 10), then B (prio 5). Power to shed = 500W.
    // C (300W) + B (200W) = 500W.
    verify(shellyService, times(1)).sendCommand("mqtt_device_C", false);
    verify(shellyService, times(1)).sendCommand("mqtt_device_B", false);
    verify(shellyService, never()).sendCommand("mqtt_device_A", false);

    verify(stateCache, times(1)).updateControlState(3L, DeviceControlState.DISABLED_BY_BALANCER);
    verify(stateCache, times(1)).updateControlState(2L, DeviceControlState.DISABLED_BY_BALANCER);

    verify(apiServiceClient, times(2))
        .notifyBalancerAction(argThat(dto -> "DISABLED_BY_BALANCER".equals(dto.getAction())));
  }

  @Test
  @DisplayName("Scenario: Overload, but no devices to turn off")
  void testOverload_whenNoDevicesToShed_thenLogsWarning() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1200.0}");

    DeviceDto device1 =
        createDeviceDto(1L, "Device1", "device/1", 1, 300, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device status - device is offline (no status available)
    when(stateCache.get(1L)).thenReturn(Optional.empty());

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - No devices should be turned off
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(stateCache, never()).updateControlState(anyLong(), any(DeviceControlState.class));
    verify(apiServiceClient, never()).notifyBalancerAction(any());
  }

  @Test
  @DisplayName("Scenario 4: Power restoration (Restore)")
  void testRestore_whenMarginAvailable_thenRestoresDevicesByPriority() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 300.0}");

    DeviceDto deviceA =
        createDeviceDto(1L, "Device A", "mqtt_device_A", 1, 200, true, 60, 20, "user");
    DeviceDto deviceB =
        createDeviceDto(2L, "Device B", "mqtt_device_B", 5, 200, true, 60, 20, "user");
    DeviceDto deviceC =
        createDeviceDto(3L, "Device C", "mqtt_device_C", 10, 400, true, 60, 20, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);
    settings.setOverloadCooldownSeconds(0);
    // Available margin = 1000 - 300 - 100 = 600W

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(deviceA, deviceB, deviceC));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device statuses - all are disabled by balancer and off
    mockDeviceDisabledByBalancer(deviceA);
    mockDeviceDisabledByBalancer(deviceB);
    mockDeviceDisabledByBalancer(deviceC);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - A (200W) and B (200W) should be restored. C (400W) should not.
    verify(shellyService, times(1)).sendCommand("mqtt_device_A", true);
    verify(shellyService, times(1)).sendCommand("mqtt_device_B", true);
    verify(shellyService, never()).sendCommand("mqtt_device_C", true);
    verify(stateCache, times(1)).updateControlState(1L, DeviceControlState.ENABLED);
    verify(stateCache, times(1)).updateControlState(2L, DeviceControlState.ENABLED);
    verify(apiServiceClient, times(2))
        .notifyBalancerAction(argThat(dto -> "ENABLED_BY_BALANCER".equals(dto.getAction())));
  }

  @Test
  @DisplayName("Scenario: Restore, but device is disabled by user")
  void testRestore_whenDeviceDisabledByUser_thenNotRestored() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 500.0}");

    DeviceDto device1 =
        createDeviceDto(1L, "Device1", "device/1", 1, 200, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device status - user disables device, and it's off
    mockDeviceState(device1, false, DeviceControlState.DISABLED_BY_USER, null);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device should NOT be restored
    verify(shellyService, never()).sendCommand(anyString(), eq(true));
  }

  @Test
  @DisplayName("Scenario: Restore, but insufficient margin")
  void testLoadDropped_InsufficientMargin_DeviceNotRestored() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 950.0}");

    DeviceDto device1 =
        createDeviceDto(1L, "Device1", "device/1", 1, 200, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100); // Available margin = 1000 - 950 - 100 = -50 (not enough)

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device status - balancer disables device
    mockDeviceDisabledByBalancer(device1);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device should NOT be restored due to insufficient margin
    verify(shellyService, never()).sendCommand(anyString(), eq(true));
    verify(stateCache, never()).updateControlState(eq(1L), eq(DeviceControlState.ENABLED));
  }

  @Test
  @DisplayName("Scenario 5: Power restoration (Cooldown active)")
  void testRestore_whenCooldownIsActive_thenNoAction() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 500.0}");
    DeviceDto deviceA =
        createDeviceDto(1L, "Device A", "mqtt_device_A", 1, 100, true, 60, 20, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);
    settings.setOverloadCooldownSeconds(60);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(List.of(deviceA));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));
    // First, device is online and ON so overload can shed it
    mockDeviceOnlineAndOn(deviceA);

    // Act: First, trigger an overload to set the timestamp (and turn device OFF)
    balancingService.balancePower(mqttPrefix, objectMapper.readTree("{\"apower\": 1200.0}"));

    // Prepare cache to reflect device disabled by balancer for the next check
    mockDeviceDisabledByBalancer(deviceA);

    // Now, act with power dropped, but cooldown is still active (time hasn't passed)
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, times(1)).sendCommand(anyString(), eq(false)); // from overload
    verify(shellyService, never()).sendCommand(anyString(), eq(true)); // no restore
  }

  @Test
  @DisplayName("Scenario 6: Power restoration (Cooldown passed)")
  void testRestore_whenCooldownHasPassed_thenRestoresDevice() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 500.0}");
    DeviceDto deviceA =
        createDeviceDto(1L, "Device A", "mqtt_device_A", 1, 100, true, 60, 20, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);
    settings.setOverloadCooldownSeconds(1); // 1 second cooldown

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(List.of(deviceA));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));
    // First, device is online and ON so overload can shed it
    mockDeviceOnlineAndOn(deviceA);

    // Act: Trigger overload, wait, then trigger restore
    balancingService.balancePower(mqttPrefix, objectMapper.readTree("{\"apower\": 1200.0}"));

    // After overload, prepare cache to reflect device disabled by balancer for restore phase
    mockDeviceDisabledByBalancer(deviceA);

    Thread.sleep(1100); // Wait for cooldown to pass
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, times(1)).sendCommand("mqtt_device_A", false); // from overload
    verify(shellyService, times(1)).sendCommand("mqtt_device_A", true); // from restore
  }

  @Test
  @DisplayName("Scenario 7: Prevent Downtime (Sufficient margin)")
  void testPreventDowntime_whenSufficientMargin_thenTurnsOnDevice() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 500.0}");
    DeviceDto deviceA =
        createDeviceDto(1L, "Refrigerator", "mqtt_device_A", 0, 200, true, 60, 0, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(List.of(deviceA));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));
    // Mock device as disabled by balancer 61 minutes ago
    mockDeviceState(
        deviceA,
        false,
        DeviceControlState.DISABLED_BY_BALANCER,
        LocalDateTime.now().minusMinutes(61));

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert (it can be turned ON twice within single balance cycle:
    // once in Prevent Downtime phase and once again in Restore phase because the mocked cache
    // still reports the device as disabled/off).
    verify(shellyService, times(2)).sendCommand("mqtt_device_A", true);
    verify(stateCache, atLeastOnce()).updateControlState(1L, DeviceControlState.ENABLED);
  }

  @Test
  @DisplayName("Scenario 8: Prevent Downtime (Sacrifice needed)")
  void testPreventDowntime_withSacrifice_thenTurnsOffOneAndOnAnother() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 900.0}"); // No margin
    DeviceDto deviceA =
        createDeviceDto(1L, "Refrigerator", "mqtt_device_A", 0, 200, true, 60, 0, "user");

    DeviceDto deviceB =
        createDeviceDto(2L, "Heater", "mqtt_device_B", 10, 300, false, 0, 0, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(deviceA, deviceB));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));
    mockDeviceState(
        deviceA,
        false,
        DeviceControlState.DISABLED_BY_BALANCER,
        LocalDateTime.now().minusMinutes(61));
    mockDeviceOnlineAndOn(deviceB);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, times(1)).sendCommand("mqtt_device_B", false); // Sacrifice
    verify(shellyService, times(1)).sendCommand("mqtt_device_A", true); // Turn on critical
  }

  @Test
  @DisplayName("Scenario 9: Prevent Downtime (Insufficient sacrifices)")
  void testPreventDowntime_withInsufficientSacrifice_thenDoesNotTurnOnCritical() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 900.0}"); // No margin
    DeviceDto deviceA =
        createDeviceDto(
            1L, "Refrigerator", "mqtt_device_A", 0, 500, true, 60, 0, "user"); // Needs 500W

    DeviceDto deviceB =
        createDeviceDto(
            2L, "Heater", "mqtt_device_B", 10, 300, false, 0, 0, "user"); // Can free 300W

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(deviceA, deviceB));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));
    mockDeviceState(
        deviceA,
        false,
        DeviceControlState.DISABLED_BY_BALANCER,
        LocalDateTime.now().minusMinutes(61));
    mockDeviceOnlineAndOn(deviceB);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert: Algorithm does not perform partial sacrifices; since 300W < needed 500W,
    // no sacrifice should happen and the critical device stays OFF.
    verify(shellyService, never()).sendCommand("mqtt_device_B", false);
    verify(shellyService, never()).sendCommand("mqtt_device_A", true);
  }

  // Helper methods

  @Test
  @DisplayName("Scenario 10: Prevent Downtime (Downtime not expired)")
  void testPreventDowntime_whenDowntimeNotExpired_thenNoAction() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 900.0}");
    DeviceDto deviceA =
        createDeviceDto(1L, "Refrigerator", "mqtt_device_A", 0, 200, true, 60, 0, "user");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(List.of(deviceA));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));
    // Mock device as disabled by balancer 30 minutes ago
    mockDeviceState(
        deviceA,
        false,
        DeviceControlState.DISABLED_BY_BALANCER,
        LocalDateTime.now().minusMinutes(30));

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
  }

  private void mockDeviceOnlineAndOn(DeviceDto device) throws Exception {
    mockDeviceState(device, true, DeviceControlState.ENABLED, null);
  }

  private void mockDeviceDisabledByBalancer(DeviceDto device) throws Exception {
    mockDeviceState(device, false, DeviceControlState.DISABLED_BY_BALANCER, LocalDateTime.now());
  }

  private void mockDeviceState(
      DeviceDto device, boolean isOn, DeviceControlState controlState, LocalDateTime disabledAt) {
    DeviceStatus deviceStatus = new DeviceStatus();
    deviceStatus.setDeviceId(device.getId());
    deviceStatus.setLastOnline(true);

    double actualPower = (device.getWattage() != null) ? device.getWattage().doubleValue() : 0.0;

    String statusJsonString =
        String.format(Locale.US, "{\"output\": %b, \"apower\": %.1f}", isOn, actualPower);
    deviceStatus.setLastStatusJson(statusJsonString);
    deviceStatus.setControlState(controlState);
    deviceStatus.setBalancerDisabledAt(disabledAt);

    when(stateCache.get(device.getId())).thenReturn(Optional.of(deviceStatus));
  }

  private DeviceDto createDeviceDto(
      Long id,
      String name,
      String mqttPrefix,
      Integer priority,
      Integer wattage,
      boolean preventDowntime,
      Integer maxDowntimeMinutes,
      Integer minUptimeMinutes,
      String username) {
    return new DeviceDto(
        id,
        name,
        mqttPrefix,
        DeviceType.SWITCHABLE_APPLIANCE,
        DeviceProvider.SHELLY,
        priority,
        wattage,
        preventDowntime,
        false,
        maxDowntimeMinutes,
        minUptimeMinutes,
        username);
  }
}
