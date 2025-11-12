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

import com.yh.sbps.device.service.SystemStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceController Unit Tests")
class DeviceControllerTest {

  private MockMvc mockMvc;
  private ObjectMapper objectMapper;
  private DeviceController controller;

  @Mock private ShellyService shellyService;
  @Mock private DeviceStatusService deviceStatusService;
  @Mock private SystemStateCache systemStateCache;

  private DeviceDto testDevice;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    controller = new DeviceController(shellyService, deviceStatusService, systemStateCache);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    testDevice =
        new DeviceDto(
            1L,
            "TestDevice",
            "test/device1",
            "SWITCHABLE_APPLIANCE",
            1,
            300,
            true,
            60,
            20,
            "username");
  }

  @Test
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
  void testTogglePlug_DeviceNotFound() throws Exception {
    // Arrange: cache doesn't have the device, DB lookup also misses
    when(systemStateCache.getStateCache()).thenReturn(Collections.emptyMap());
    when(deviceStatusService.findMqttPrefixById(1L)).thenReturn(Optional.empty());

    // Act & Assert
    mockMvc
        .perform(post("/api/device/plug/1/toggle").param("on", "true"))
        .andExpect(status().isNotFound());

    verify(shellyService, never()).sendCommand(anyString(), anyBoolean());
    verify(deviceStatusService, never())
        .updateControlState(anyLong(), any(DeviceControlState.class));
  }

  @Test
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
  void testGetStatus_NotFound() throws Exception {
    // Arrange
    when(deviceStatusService.getStatusAsJsonNode(1L)).thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/device/plug/1/status")).andExpect(status().isNotFound());
  }

  @Test
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
  void testGetOnline_NotFound() throws Exception {
    // Arrange
    when(deviceStatusService.getOnlineStatus(1L)).thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/device/plug/1/online")).andExpect(status().isNotFound());
  }

  @Test
  void testGetEvents_Success() throws Exception {
    // Arrange
    JsonNode eventJson =
        objectMapper.readTree("{\"method\":\"Switch.Toggle\",\"params\":{\"id\":0}}");
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
  void testGetEvents_NotFound() throws Exception {
    // Arrange
    when(deviceStatusService.getEventAsJsonNode(1L)).thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/device/plug/1/events")).andExpect(status().isNotFound());
  }
}
