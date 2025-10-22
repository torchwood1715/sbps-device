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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "mqtt.broker-url=tcp://localhost:1883",
      "mqtt.client-id=test-client",
      "api.base-url=http://localhost:8080",
      "service-user.email=test@test.com",
      "service-user.password=test"
    })
@DisplayName("ShellyService Integration Tests")
class ShellyServiceIntegrationTest {

  @Autowired private ShellyService shellyService;

  @Autowired private DeviceStatusService deviceStatusService;

  @Autowired private DeviceStatusRepository deviceStatusRepository;

  @MockitoBean private ApiServiceClient apiServiceClient;

  @MockitoBean private BalancingService balancingService;

  @Autowired private ObjectMapper objectMapper;

  private DeviceDto testDevice1;
  private DeviceDto testDevice2;
  private DeviceDto powerMonitorDevice;

  @BeforeEach
  void setUp() {
    // Clear database before each test
    deviceStatusRepository.deleteAll();

    // Setup test devices
    testDevice1 = new DeviceDto(1L, "TestDevice1", "test/device1", "SWITCHABLE_APPLIANCE", 1, 300);
    testDevice2 = new DeviceDto(2L, "TestDevice2", "test/device2", "SWITCHABLE_APPLIANCE", 2, 400);
    powerMonitorDevice =
        new DeviceDto(3L, "PowerMonitor", "test/monitor", "POWER_MONITOR", null, null);

    // Mock ApiServiceClient to return test devices
    when(apiServiceClient.getAllDevices())
        .thenReturn(Arrays.asList(testDevice1, testDevice2, powerMonitorDevice));

    // Inject BalancingService into ShellyService
    shellyService.setBalancingService(balancingService);
  }

  @Test
  @DisplayName("Тест 1: Обробка MQTT повідомлення про статус online")
  void testHandleMqttMessage_OnlineStatus() {
    // Arrange
    String topic = "test/device1/online";
    String payload = "true";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = deviceStatusRepository.findByDeviceId(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastOnline()).isTrue();
    assertThat(deviceStatus.get().getMqttPrefix()).isEqualTo("test/device1");
  }

  @Test
  @DisplayName("Тест 2: Обробка MQTT повідомлення про статус offline")
  void testHandleMqttMessage_OfflineStatus() {
    // Arrange
    String topic = "test/device1/online";
    String payload = "false";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = deviceStatusRepository.findByDeviceId(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastOnline()).isFalse();
  }

  @Test
  @DisplayName("Тест 3: Обробка MQTT повідомлення про статус пристрою (switch)")
  void testHandleMqttMessage_SwitchStatus() throws Exception {
    // Arrange
    String topic = "test/device1/status/switch:0";
    String payload = "{\"id\":0,\"output\":true,\"apower\":150.5,\"voltage\":230.0}";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = deviceStatusRepository.findByDeviceId(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastStatusJson()).isNotNull();

    JsonNode statusJson = objectMapper.readTree(deviceStatus.get().getLastStatusJson());
    assertThat(statusJson.get("output").asBoolean()).isTrue();
    assertThat(statusJson.get("apower").asDouble()).isEqualTo(150.5);
  }

  @Test
  @DisplayName("Тест 4: Обробка MQTT повідомлення від монітора потужності -> викликається балансування")
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
    verify(balancingService, times(1))
        .balancePower(eq("test/monitor"), any(JsonNode.class));

    // Verify that device status was updated
    Optional<DeviceStatus> deviceStatus = deviceStatusRepository.findByDeviceId(3L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastStatusJson()).isNotNull();
  }

  @Test
  @DisplayName("Тест 5: Обробка MQTT повідомлення про події (events/rpc)")
  void testHandleMqttMessage_EventsRpc() throws Exception {
    // Arrange
    String topic = "test/device1/events/rpc";
    String payload = "{\"method\":\"Switch.Toggle\",\"params\":{\"id\":0}}";

    Message<String> message =
        MessageBuilder.withPayload(payload).setHeader(MqttHeaders.RECEIVED_TOPIC, topic).build();

    // Act
    shellyService.handleMqttMessage(message);

    // Assert
    Optional<DeviceStatus> deviceStatus = deviceStatusRepository.findByDeviceId(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastEventJson()).isNotNull();

    JsonNode eventJson = objectMapper.readTree(deviceStatus.get().getLastEventJson());
    assertThat(eventJson.get("method").asText()).isEqualTo("Switch.Toggle");
  }

  @Test
  @DisplayName("Тест 6: Перевірка обробки кількох повідомлень від одного пристрою")
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
    shellyService.handleMqttMessage(message2);

    // Assert
    // Verify both messages were processed correctly
    Optional<DeviceStatus> deviceStatus = deviceStatusRepository.findByDeviceId(1L);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastOnline()).isTrue();
    assertThat(deviceStatus.get().getLastStatusJson()).isNotNull();

    JsonNode statusJson = objectMapper.readTree(deviceStatus.get().getLastStatusJson());
    assertThat(statusJson.get("output").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Тест 7: Обробка невідомого MQTT префіксу")
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
  @DisplayName("Тест 8: Перевірка DeviceStatusService інтеграції")
  void testDeviceStatusServiceIntegration() throws Exception {
    // Arrange
    Long deviceId = 1L;
    JsonNode statusJson = objectMapper.readTree("{\"output\":true,\"apower\":200.0}");

    // Act
    deviceStatusService.updateStatus(deviceId, statusJson, "test/device1");
    deviceStatusService.updateOnline(deviceId, true, "test/device1");

    // Assert
    Optional<DeviceStatus> deviceStatus = deviceStatusService.findByDeviceId(deviceId);
    assertThat(deviceStatus).isPresent();
    assertThat(deviceStatus.get().getLastOnline()).isTrue();

    JsonNode retrievedStatus = deviceStatusService.getStatusAsJsonNode(deviceId);
    assertThat(retrievedStatus).isNotNull();
    assertThat(retrievedStatus.get("output").asBoolean()).isTrue();
    assertThat(retrievedStatus.get("apower").asDouble()).isEqualTo(200.0);

    Boolean onlineStatus = deviceStatusService.getOnlineStatus(deviceId);
    assertThat(onlineStatus).isTrue();
  }
}

