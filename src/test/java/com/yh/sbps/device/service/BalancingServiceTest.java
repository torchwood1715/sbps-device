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
import java.util.Locale;
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

  @Mock private SystemStateCache systemStateCache;

  @Mock private DeviceStatusService deviceStatusService;

  @Mock private ShellyService shellyService;

  @Mock private ApiServiceClient apiServiceClient;

  @InjectMocks private BalancingService balancingService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    // Inject ShellyService manually to avoid circular dependency
    balancingService.setShellyService(shellyService);
  }

  @Test
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

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(deviceStatusService, never())
        .updateControlState(anyLong(), any(DeviceControlState.class));
  }

  @Test
  void testOverload_TurnsOffLowestPriorityDevice() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1200.0}");

    DeviceDto device1 =
        new DeviceDto(
            1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 300, false, 60, 20, "username");
    DeviceDto device2 =
        new DeviceDto(
            2L, "Device2", "device/2", "SWITCHABLE_APPLIANCE", 2, 400, false, 0, 0, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device statuses - both devices are online and ON
    mockDeviceOnlineAndOn(device1);
    mockDeviceOnlineAndOn(device2);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device2 has lower priority (higher number), so it should be turned off first
    verify(shellyService, times(1)).sendCommand("device/2", false);
    verify(deviceStatusService, times(1))
        .updateControlState(2L, DeviceControlState.DISABLED_BY_BALANCER);
  }

  @Test
  void testOverload_TurnsOffMultipleDevices() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1500.0}");

    DeviceDto device1 =
        new DeviceDto(
            1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200, true, 60, 20, "username");
    DeviceDto device2 =
        new DeviceDto(
            2L, "Device2", "device/2", "SWITCHABLE_APPLIANCE", 2, 300, true, 60, 20, "username");
    DeviceDto device3 =
        new DeviceDto(
            3L, "Device3", "device/3", "SWITCHABLE_APPLIANCE", 3, 400, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2, device3));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device statuses - all devices are online and ON
    mockDeviceOnlineAndOn(device1);
    mockDeviceOnlineAndOn(device2);
    mockDeviceOnlineAndOn(device3);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Should turn off device3 (priority 3) and device2 (priority 2)
    verify(shellyService, times(1)).sendCommand("device/3", false);
    verify(shellyService, times(1)).sendCommand("device/2", false);
    verify(deviceStatusService, times(1))
        .updateControlState(3L, DeviceControlState.DISABLED_BY_BALANCER);
    verify(deviceStatusService, times(1))
        .updateControlState(2L, DeviceControlState.DISABLED_BY_BALANCER);
    verify(apiServiceClient, times(2))
        .notifyBalancerAction(argThat(dto -> "DISABLED_BY_BALANCER".equals(dto.getAction())));
  }

  @Test
  void testOverload_NoAvailableDevices_NothingTurnedOff() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 1200.0}");

    DeviceDto device1 =
        new DeviceDto(
            1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 300, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device status - device is offline (no status available)
    when(deviceStatusService.findByDeviceId(1L)).thenReturn(Optional.empty());

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - No devices should be turned off
    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(deviceStatusService, never())
        .updateControlState(anyLong(), any(DeviceControlState.class));
    verify(apiServiceClient, never()).notifyBalancerAction(any());
  }

  @Test
  void testLoadDropped_DevicesRestoredByPriority() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 300.0}");

    DeviceDto device1 =
        new DeviceDto(
            1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200, true, 60, 20, "username");
    DeviceDto device2 =
        new DeviceDto(
            2L, "Device2", "device/2", "SWITCHABLE_APPLIANCE", 2, 300, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);
    // Available margin = 1000 - 300 - 100 = 600W (enough for both devices)

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Arrays.asList(device1, device2));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device statuses - balancer disables both devices
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
    verify(apiServiceClient, times(2))
        .notifyBalancerAction(argThat(dto -> "ENABLED_BY_BALANCER".equals(dto.getAction())));
  }

  @Test
  void testLoadDropped_DeviceDisabledByUser_NotRestored() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 500.0}");

    DeviceDto device1 =
        new DeviceDto(
            1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100);

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device status - user disables device
    when(deviceStatusService.getControlState(1L)).thenReturn(DeviceControlState.DISABLED_BY_USER);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device should NOT be restored
    verify(shellyService, never()).sendCommand(anyString(), eq(true));
    verify(deviceStatusService, never()).updateControlState(eq(1L), eq(DeviceControlState.ENABLED));
  }

  @Test
  void testLoadDropped_InsufficientMargin_DeviceNotRestored() throws Exception {
    // Arrange
    String mqttPrefix = "monitor/device1";
    JsonNode powerMonitorStatus = objectMapper.readTree("{\"apower\": 950.0}");

    DeviceDto device1 =
        new DeviceDto(
            1L, "Device1", "device/1", "SWITCHABLE_APPLIANCE", 1, 200, true, 60, 20, "username");

    SystemSettingsDto settings = new SystemSettingsDto();
    settings.setPowerLimitWatts(1000);
    settings.setPowerOnMarginWatts(100); // Available margin = 1000 - 950 - 100 = -50 (not enough)

    SystemStateDto systemState = new SystemStateDto();
    systemState.setSystemSettings(settings);
    systemState.setDevices(Collections.singletonList(device1));

    when(systemStateCache.getState(mqttPrefix)).thenReturn(Optional.of(systemState));

    // Mock device status - balancer disables device
    when(deviceStatusService.getControlState(1L))
        .thenReturn(DeviceControlState.DISABLED_BY_BALANCER);

    // Act
    balancingService.balancePower(mqttPrefix, powerMonitorStatus);

    // Assert - Device should NOT be restored due to insufficient margin
    verify(shellyService, never()).sendCommand(anyString(), eq(true));
    verify(deviceStatusService, never()).updateControlState(eq(1L), eq(DeviceControlState.ENABLED));
  }

  // Helper methods

  private void mockDeviceOnlineAndOn(DeviceDto device) throws Exception {
    DeviceStatus deviceStatus = new DeviceStatus();
    deviceStatus.setDeviceId(device.getId());
    deviceStatus.setLastOnline(true);

    double actualPower = (device.getWattage() != null) ? device.getWattage().doubleValue() : 0.0;

    String statusJsonString =
        String.format(Locale.US, "{\"output\": true, \"apower\": %.1f}", actualPower);
    deviceStatus.setLastStatusJson(statusJsonString);
    JsonNode statusNode = objectMapper.readTree(statusJsonString);

    when(deviceStatusService.findByDeviceId(device.getId())).thenReturn(Optional.of(deviceStatus));

    when(deviceStatusService.getStatusAsJsonNode(device.getId())).thenReturn(statusNode);
  }
}
