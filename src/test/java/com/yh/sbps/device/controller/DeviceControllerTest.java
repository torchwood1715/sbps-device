package com.yh.sbps.device.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.entity.DeviceStatus.DeviceControlState;
import com.yh.sbps.device.integration.ApiServiceClient;
import com.yh.sbps.device.service.DeviceStatusService;
import com.yh.sbps.device.service.ShellyService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DeviceController.class)
@DisplayName("DeviceController Unit Tests")
class DeviceControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ShellyService shellyService;

  @MockitoBean private ApiServiceClient apiServiceClient;

  @MockitoBean private DeviceStatusService deviceStatusService;

  private DeviceDto testDevice;

  @BeforeEach
  void setUp() {
    testDevice = new DeviceDto(1L, "TestDevice", "test/device1", "SWITCHABLE_APPLIANCE", 1, 300);
  }

  @Test
  @DisplayName("POST /api/device/internal/subscribe - успішна підписка на пристрій")
  void testSubscribeDevice_Success() throws Exception {
    // Arrange
    String deviceJson = objectMapper.writeValueAsString(testDevice);

    // Act & Assert
    mockMvc
        .perform(
            post("/api/device/internal/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deviceJson))
        .andExpect(status().isOk());

    verify(shellyService, times(1)).subscribeForDevice(any(DeviceDto.class));
  }

  @Test
  @DisplayName("POST /api/device/internal/subscribe - помилка при підписці")
  void testSubscribeDevice_Error() throws Exception {
    // Arrange
    String deviceJson = objectMapper.writeValueAsString(testDevice);
    doThrow(new RuntimeException("MQTT error")).when(shellyService).subscribeForDevice(any());

    // Act & Assert
    mockMvc
        .perform(
            post("/api/device/internal/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deviceJson))
        .andExpect(status().isInternalServerError());
  }

  @Test
  @DisplayName("POST /api/device/plug/{deviceId}/toggle - увімкнення пристрою")
  void testTogglePlug_TurnOn_Success() throws Exception {
    // Arrange
    when(apiServiceClient.getDeviceById(1L)).thenReturn(Optional.of(testDevice));

    // Act & Assert
    mockMvc
        .perform(post("/api/device/plug/1/toggle").param("on", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string("Device [TestDevice] toggled ON by user"));

    verify(shellyService, times(1)).sendCommand("test/device1", true);
    verify(deviceStatusService, times(1)).updateControlState(1L, DeviceControlState.ENABLED);
  }

  @Test
  @DisplayName("POST /api/device/plug/{deviceId}/toggle - вимкнення пристрою")
  void testTogglePlug_TurnOff_Success() throws Exception {
    // Arrange
    when(apiServiceClient.getDeviceById(1L)).thenReturn(Optional.of(testDevice));

    // Act & Assert
    mockMvc
        .perform(post("/api/device/plug/1/toggle").param("on", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string("Device [TestDevice] toggled OFF by user"));

    verify(shellyService, times(1)).sendCommand("test/device1", false);
    verify(deviceStatusService, times(1))
        .updateControlState(1L, DeviceControlState.DISABLED_BY_USER);
  }

  @Test
  @DisplayName("POST /api/device/plug/{deviceId}/toggle - пристрій не знайдено")
  void testTogglePlug_DeviceNotFound() throws Exception {
    // Arrange
    when(apiServiceClient.getDeviceById(1L)).thenReturn(Optional.empty());

    // Act & Assert
    mockMvc
        .perform(post("/api/device/plug/1/toggle").param("on", "true"))
        .andExpect(status().isNotFound());

    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(deviceStatusService, never())
        .updateControlState(anyLong(), any(DeviceControlState.class));
  }

  @Test
  @DisplayName("POST /api/device/plug/{deviceId}/toggle - пристрій без MQTT префіксу")
  void testTogglePlug_NoMqttPrefix() throws Exception {
    // Arrange
    DeviceDto deviceWithoutMqtt = new DeviceDto(1L, "TestDevice", null, "SWITCHABLE_APPLIANCE", 1, 300);
    when(apiServiceClient.getDeviceById(1L)).thenReturn(Optional.of(deviceWithoutMqtt));

    // Act & Assert
    mockMvc
        .perform(post("/api/device/plug/1/toggle").param("on", "true"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("Device has no MQTT prefix configured"));

    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/status - отримання статусу пристрою")
  void testGetStatus_Success() throws Exception {
    // Arrange
    JsonNode statusJson = objectMapper.readTree("{\"output\":true,\"apower\":150.5}");
    when(deviceStatusService.getStatusAsJsonNode(1L)).thenReturn(statusJson);

    // Act & Assert
    mockMvc
        .perform(get("/api/device/plug/1/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.output").value(true))
        .andExpect(jsonPath("$.apower").value(150.5));

    verify(deviceStatusService, times(1)).getStatusAsJsonNode(1L);
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/status - статус не знайдено")
  void testGetStatus_NotFound() throws Exception {
    // Arrange
    when(deviceStatusService.getStatusAsJsonNode(1L)).thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/device/plug/1/status")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/online - пристрій онлайн")
  void testGetOnline_DeviceOnline() throws Exception {
    // Arrange
    when(deviceStatusService.getOnlineStatus(1L)).thenReturn(true);

    // Act & Assert
    mockMvc
        .perform(get("/api/device/plug/1/online"))
        .andExpect(status().isOk())
        .andExpect(content().string("true"));

    verify(deviceStatusService, times(1)).getOnlineStatus(1L);
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/online - пристрій офлайн")
  void testGetOnline_DeviceOffline() throws Exception {
    // Arrange
    when(deviceStatusService.getOnlineStatus(1L)).thenReturn(false);

    // Act & Assert
    mockMvc
        .perform(get("/api/device/plug/1/online"))
        .andExpect(status().isOk())
        .andExpect(content().string("false"));
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/online - статус не знайдено")
  void testGetOnline_NotFound() throws Exception {
    // Arrange
    when(deviceStatusService.getOnlineStatus(1L)).thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/device/plug/1/online")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/events - отримання подій пристрою")
  void testGetEvents_Success() throws Exception {
    // Arrange
    JsonNode eventJson = objectMapper.readTree("{\"method\":\"Switch.Toggle\",\"params\":{\"id\":0}}");
    when(deviceStatusService.getEventAsJsonNode(1L)).thenReturn(eventJson);

    // Act & Assert
    mockMvc
        .perform(get("/api/device/plug/1/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.method").value("Switch.Toggle"))
        .andExpect(jsonPath("$.params.id").value(0));

    verify(deviceStatusService, times(1)).getEventAsJsonNode(1L);
  }

  @Test
  @DisplayName("GET /api/device/plug/{deviceId}/events - події не знайдено")
  void testGetEvents_NotFound() throws Exception {
    // Arrange
    when(deviceStatusService.getEventAsJsonNode(1L)).thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/device/plug/1/events")).andExpect(status().isNotFound());
  }
}

