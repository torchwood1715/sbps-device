package com.yh.sbps.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.entity.DeviceStatus;
import com.yh.sbps.device.integration.ApiServiceClient;
import com.yh.sbps.device.repository.DeviceStatusRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {"jwt.secret=01234567890123456789012345678901", "spring.liquibase.enabled=false"})
@DisplayName("ShellyService Integration Tests")
class ShellyServiceIntegrationTest {

  @Autowired private ShellyService shellyService;

  @Autowired private DeviceStatusService deviceStatusService;

  @Autowired private DeviceStatusRepository deviceStatusRepository;

  @MockitoBean private ApiServiceClient apiServiceClient;

  @MockitoBean private BalancingService balancingService;

  // Security beans mocked to avoid loading real security config
  @MockitoBean private com.yh.sbps.device.config.security.JwtAuthFilter jwtAuthFilter;

  @MockitoBean
  private com.yh.sbps.device.config.security.DeviceUserDetailsService deviceUserDetailsService;

  @MockitoBean private com.yh.sbps.device.config.security.JwtService jwtService;

  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    // Clear database before each test
    deviceStatusRepository.deleteAll();

    // Setup test devices
    DeviceDto testDevice1 =
        new DeviceDto(
            1L,
            "TestDevice1",
            "test/device1",
            "SWITCHABLE_APPLIANCE",
            1,
            300,
            true,
            60,
            20,
            "username");
    DeviceDto testDevice2 =
        new DeviceDto(
            2L,
            "TestDevice2",
            "test/device2",
            "SWITCHABLE_APPLIANCE",
            2,
            400,
            true,
            60,
            20,
            "username");
    DeviceDto powerMonitorDevice =
        new DeviceDto(
            3L,
            "PowerMonitor",
            "test/monitor",
            "POWER_MONITOR",
            null,
            null,
            false,
            0,
            0,
            "username");

    // Mock ApiServiceClient to return test devices
    when(apiServiceClient.getAllDevices())
        .thenReturn(Arrays.asList(testDevice1, testDevice2, powerMonitorDevice));

    // Pre-populate ShellyService device cache so it can resolve topics to device IDs
    shellyService.refreshDeviceCache(testDevice1);
    shellyService.refreshDeviceCache(testDevice2);
    shellyService.refreshDeviceCache(powerMonitorDevice);

    // Inject BalancingService into ShellyService
    shellyService.setBalancingService(balancingService);
  }

  private Optional<DeviceStatus> awaitDevice(Long deviceId) {
    for (int i = 0; i < 60; i++) { // up to ~3s
      Optional<DeviceStatus> opt = deviceStatusRepository.findByDeviceId(deviceId);
      if (opt.isPresent()) return opt;
      try {
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
      }
    }
    return deviceStatusRepository.findByDeviceId(deviceId);
  }

  @Test
  void testHandleMqttMessage_OnlineStatus() {
    // Arrange
    String topic = "test/device1/online";
    String payload = "true";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = awaitDevice(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastOnline()).isTrue();
    assertThat(deviceStatus.get().getMqttPrefix()).isEqualTo("test/device1");
  }

  @Test
  void testHandleMqttMessage_OfflineStatus() {
    // Arrange
    String topic = "test/device1/online";
    String payload = "false";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = awaitDevice(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastOnline()).isFalse();
  }

  @Test
  void testHandleMqttMessage_SwitchStatus() throws Exception {
    // Arrange
    String topic = "test/device1/status/switch:0";
    String payload = "{\"id\":0,\"output\":true,\"apower\":150.5,\"voltage\":230.0}";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = awaitDevice(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastStatusJson()).isNotNull();

    JsonNode statusJson = objectMapper.readTree(deviceStatus.get().getLastStatusJson());
    assertThat(statusJson.get("output").asBoolean()).isTrue();
    assertThat(statusJson.get("apower").asDouble()).isEqualTo(150.5);
  }

  @Test
  void testHandleMqttMessage_PowerMonitor_TriggersBalancing() throws Exception {
    // Arrange
    String topic = "test/monitor/status/switch:0";
    String payload = "{\"id\":0,\"apower\":1200.0,\"voltage\":230.0}";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    // Verify that balancePower was called
    verify(balancingService, times(1)).balancePower(eq("test/monitor"), any(JsonNode.class));

    // Verify that device status was updated
    Optional<DeviceStatus> deviceStatus = awaitDevice(3L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastStatusJson()).isNotNull();
  }

  @Test
  void testHandleMqttMessage_EventsRpc() throws Exception {
    // Arrange
    String topic = "test/device1/events/rpc";
    String payload = "{\"method\":\"Switch.Toggle\",\"params\":{\"id\":0}}";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = awaitDevice(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastEventJson()).isNotNull();

    JsonNode eventJson = objectMapper.readTree(deviceStatus.get().getLastEventJson());
    assertThat(eventJson.get("method").asText()).isEqualTo("Switch.Toggle");
  }

  @Test
  void testMultipleMessagesFromSameDevice() throws Exception {
    // Arrange
    String topic1 = "test/device1/online";
    String payload1 = "true";

    String topic2 = "test/device1/status/switch:0";
    String payload2 = "{\"id\":0,\"output\":true}";

    Message<String> message1 =
        MessageBuilder.withPayload(payload1).setHeader(MqttHeaders.RECEIVED_TOPIC, topic1).build();

    Message<String> message2 =
        MessageBuilder.withPayload(payload2).setHeader(MqttHeaders.RECEIVED_TOPIC, topic2).build();

    // Act
    shellyService.handleMqttMessage(message1);
    // Await for first async write to complete to avoid PK race
    Thread.sleep(100);
    shellyService.handleMqttMessage(message2);

    // Assert
    // Verify both messages were processed correctly (wait until status JSON is stored)
    Optional<DeviceStatus> deviceStatus = awaitDevice(1L);
    assertThat(deviceStatus).isPresent();
    // wait up to ~2s for status json to appear due to @Async writes
    for (int i = 0;
        i < 40
            && (deviceStatus.get().getLastStatusJson() == null
                || deviceStatus.get().getLastOnline() == null
                || !deviceStatus.get().getLastOnline());
        i++) {
      Thread.sleep(50);
      deviceStatus = deviceStatusRepository.findByDeviceId(1L);
      assertThat(deviceStatus).isPresent();
    }
    assertThat(deviceStatus.get().getLastOnline()).isTrue();
    assertThat(deviceStatus.get().getLastStatusJson()).isNotNull();

    JsonNode statusJson = objectMapper.readTree(deviceStatus.get().getLastStatusJson());
    assertThat(statusJson.get("output").asBoolean()).isTrue();
  }

  @Test
  void testHandleMqttMessage_UnknownMqttPrefix() {
    // Arrange
    String topic = "unknown/device/online";
    String payload = "true";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    // No device status should be created for unknown device
    List<DeviceStatus> allStatuses = deviceStatusRepository.findAll();
    assertThat(allStatuses).isEmpty();
  }

  @Test
  void testDeviceStatusServiceIntegration() throws Exception {
    // Arrange
    Long deviceId = 1L;
    JsonNode statusJson = objectMapper.readTree("{\"output\":true,\"apower\":200.0}");

    // Act
    deviceStatusService.updateStatus(deviceId, statusJson, "test/device1");
    // Await for first async write to complete to avoid PK race
    awaitDevice(deviceId);
    deviceStatusService.updateOnline(deviceId, true, "test/device1");

    // Assert - await async persistence
    Optional<DeviceStatus> deviceStatus = awaitDevice(deviceId);
    assertThat(deviceStatus).isPresent();
    for (int i = 0;
        i < 40
            && (deviceStatus.get().getLastStatusJson() == null
                || deviceStatus.get().getLastOnline() == null
                || !deviceStatus.get().getLastOnline());
        i++) {
      Thread.sleep(50);
      deviceStatus = deviceStatusRepository.findByDeviceId(deviceId);
      assertThat(deviceStatus).isPresent();
    }
    assertThat(deviceStatus.get().getLastOnline()).isTrue();

    JsonNode retrievedStatus = objectMapper.readTree(deviceStatus.get().getLastStatusJson());
    assertThat(retrievedStatus).isNotNull();
    assertThat(retrievedStatus.get("output").asBoolean()).isTrue();
    assertThat(retrievedStatus.get("apower").asDouble()).isEqualTo(200.0);

    Boolean onlineStatus = deviceStatus.get().getLastOnline();
    assertThat(onlineStatus).isTrue();
  }
}
