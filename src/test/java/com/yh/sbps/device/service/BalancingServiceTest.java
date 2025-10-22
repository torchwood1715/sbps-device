package com.yh.sbps.device.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.SystemSettingsDto;
import com.yh.sbps.device.dto.SystemStateDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import com.yh.sbps.device.integration.ApiServiceClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BalancingService Unit Tests")
class BalancingServiceTest {

  @Mock private ApiServiceClient apiServiceClient;

  @Mock private DeviceStatusService deviceStatusService;

  @Mock private ShellyService shellyService;

  @InjectMocks private BalancingService balancingService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    // Inject ShellyService manually to avoid circular dependency
    balancingService.setShellyService(shellyService);
  }

  @Test
  @DisplayName("Сценарій 1: Немає перевантаження -> нічого не вимикається")
  void testNoOverload_NoDevicesTurnedOff() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 800.0}");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.emptyList());

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(deviceStatusService, never())
        .updateControlState(anyLong(), any(DeviceControlState.class));
  }

  @Test
  @DisplayName("Сценарій 2: Є перевантаження -> вимикається пристрій з найнижчим пріоритетом")
  void testOverload_TurnsOffLowestPriorityDevice() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1200.0}");

    DeviceDto device1 =
        new DeviceDto(1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 300);
    DeviceDto device2 =
        new DeviceDto(2L, "Device2", "device/2", "SWITCHABLE_APPLIANCE", 2, 400);

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2));

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Mock device statuses - both devices are online and ON
    mockDeviceOnlineAndOn(1L, true);
    mockDeviceOnlineAndOn(2L, true);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device2 has lower priority (higher number), so it should be turned off first
    verify(shellyService, times(1)).sendCommand("device/2", false);
    verify(deviceStatusService, times(1))
        .updateControlState(2L, DeviceControlState.DISABLED_BY_BALANCER);
  }

  @Test
  @DisplayName("Сценарій 3: Є перевантаження -> вимикається кілька пристроїв")
  void testOverload_TurnsOffMultipleDevices() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1500.0}");

    DeviceDto device1 =
        new DeviceDto(1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200);
    DeviceDto device2 =
        new DeviceDto(2L, "Device2", "device/2", "SWITCHABLE_APPLIANCE", 2, 300);
    DeviceDto device3 =
        new DeviceDto(3L, "Device3", "device/3", "SWITCHABLE_APPLIANCE", 3, 400);

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2, device3));

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Mock device statuses - all devices are online and ON
    mockDeviceOnlineAndOn(1L, true);
    mockDeviceOnlineAndOn(2L, true);
    mockDeviceOnlineAndOn(3L, true);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Should turn off device3 (priority 3) and device2 (priority 2)
    verify(shellyService, times(1)).sendCommand("device/3", false);
    verify(shellyService, times(1)).sendCommand("device/2", false);
    verify(deviceStatusService, times(1))
        .updateControlState(3L, DeviceControlState.DISABLED_BY_BALANCER);
    verify(deviceStatusService, times(1))
        .updateControlState(2L, DeviceControlState.DISABLED_BY_BALANCER);
  }

  @Test
  @DisplayName(
      "Сценарій 4: Є перевантаження, але немає доступних пристроїв -> нічого не вимикається")
  void testOverload_NoAvailableDevices_NothingTurnedOff() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1200.0}");

    DeviceDto device1 =
        new DeviceDto(1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 300);

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Mock device status - device is offline (no status available)
    when(deviceStatusService.findByDeviceId(1L)).thenReturn(Optional.empty());

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - No devices should be turned off
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(deviceStatusService, never())
        .updateControlState(anyLong(), any(DeviceControlState.class));
  }

  @Test
  @DisplayName("Сценарій 5: Навантаження впало -> пристрої вмикаються в порядку пріоритету")
  void testLoadDropped_DevicesRestoredByPriority() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 300.0}");

    DeviceDto device1 =
        new DeviceDto(1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200);
    DeviceDto device2 =
        new DeviceDto(2L, "Device2", "device/2", "SWITCHABLE_APPLIANCE", 2, 300);

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);
    // Available margin = 1000 - 300 - 100 = 600W (enough for both devices)

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2));

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Mock device statuses - both devices are disabled by balancer
    when(deviceStatusService.getControlState(1L))
        .thenReturn(DeviceControlState.DISABLED_BY_BALANCER);
    when(deviceStatusService.getControlState(2L))
        .thenReturn(DeviceControlState.DISABLED_BY_BALANCER);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device1 (priority 1) should be restored first, then Device2
    verify(shellyService, times(1)).sendCommand("device/1", true);
    verify(shellyService, times(1)).sendCommand("device/2", true);
    verify(deviceStatusService, times(1)).updateControlState(1L, DeviceControlState.ENABLED);
    verify(deviceStatusService, times(1)).updateControlState(2L, DeviceControlState.ENABLED);
  }

  @Test
  @DisplayName(
      "Сценарій 6: Навантаження впало, але пристрій вимкнений користувачем -> не вмикається")
  void testLoadDropped_DeviceDisabledByUser_NotRestored() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 500.0}");

    DeviceDto device1 =
        new DeviceDto(1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200);

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Mock device status - device is disabled by user
    when(deviceStatusService.getControlState(1L))
        .thenReturn(DeviceControlState.DISABLED_BY_USER);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device should NOT be restored
    verify(shellyService, never()).sendCommand(anyString(), eq(true));
    verify(deviceStatusService, never()).updateControlState(eq(1L), eq(DeviceControlState.ENABLED));
  }

  @Test
  @DisplayName("Сценарій 7: Недостатньо запасу потужності для відновлення пристрою")
  void testLoadDropped_InsufficientMargin_DeviceNotRestored() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 950.0}");

    DeviceDto device1 =
        new DeviceDto(1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200);

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100); // Available margin = 1000 - 950 - 100 = -50 (not enough)

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(apiServiceClient.getSystemStateByMqttPrefix(mqttPrefix))
        .thenReturn(Optional.of(systemState));

    // Mock device status - device is disabled by balancer
    when(deviceStatusService.getControlState(1L))
        .thenReturn(DeviceControlState.DISABLED_BY_BALANCER);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device should NOT be restored due to insufficient margin
    verify(shellyService, never()).sendCommand(anyString(), eq(true));
    verify(deviceStatusService, never()).updateControlState(eq(1L), eq(DeviceControlState.ENABLED));
  }

  // Helper methods

  private void mockDeviceOnlineAndOn(Long deviceId, boolean isOnlineAndOn) throws Exception {
    DeviceStatus deviceStatus = new DeviceStatus();
    deviceStatus.setDeviceId(deviceId);
    deviceStatus.setLastOnline(isOnlineAndOn);

    if (isOnlineAndOn) {
      deviceStatus.setLastStatusJson("{\"output\": true}");
      JsonNode statusNode = objectMapper.readTree("{\"output\": true}");
      when(deviceStatusService.getStatusAsJsonNode(deviceId)).thenReturn(statusNode);
    } else {
      deviceStatus.setLastStatusJson("{\"output\": false}");
      JsonNode statusNode = objectMapper.readTree("{\"output\": false}");
      when(deviceStatusService.getStatusAsJsonNode(deviceId)).thenReturn(statusNode);
    }

    when(deviceStatusService.findByDeviceId(deviceId)).thenReturn(Optional.of(deviceStatus));
  }
}

