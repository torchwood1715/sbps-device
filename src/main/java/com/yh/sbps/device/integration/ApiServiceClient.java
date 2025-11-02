package com.yh.sbps.device.integration;

import com.yh.sbps.device.dto.DeviceDto;
import com.yh.sbps.device.dto.DeviceStatusUpdateDto;
import com.yh.sbps.device.dto.SystemStateDto;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class ApiServiceClient {

  public static final String TOKEN_REFRESH_AND_SINGLE_RETRY =
      "Received {} on {} {}. Attempting token refresh and single retry...";
  private static final Logger logger = LoggerFactory.getLogger(ApiServiceClient.class);
  private final RestTemplate restTemplate;
  private final String baseUrl;
  private final AuthService authService;

  public ApiServiceClient(@Value("${api.base-url}") String baseUrl, AuthService authService) {
    this.restTemplate = new RestTemplate();
    this.baseUrl = baseUrl;
    this.authService = authService;
    logger.info("ApiServiceClient initialized with base URL: {}", baseUrl);
  }

  /**
   * Creates HTTP headers with Authorization Bearer token.
   *
   * @return HttpHeaders with Authorization header
   */
  private HttpHeaders createAuthHeaders() {
    HttpHeaders headers = new HttpHeaders();
    String token = authService.getAuthToken();
    if (token != null) {
      headers.set("Authorization", "Bearer " + token);
    }
    return headers;
  }

  private boolean isUnauthorizedOrForbidden(HttpClientErrorException e) {
    return e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403;
  }

  private <T> ResponseEntity<T> exchangeWithRetry(
      String url, HttpMethod method, Object body, Class<T> responseType) {
    try {
      HttpEntity<Object> request = new HttpEntity<>(body, createAuthHeaders());
      return restTemplate.exchange(url, method, request, responseType);
    } catch (HttpClientErrorException e) {
      if (isUnauthorizedOrForbidden(e)) {
        logger.warn(TOKEN_REFRESH_AND_SINGLE_RETRY, e.getStatusCode().value(), method, url);
        authService.refreshToken();
        HttpEntity<Object> retryRequest = new HttpEntity<>(body, createAuthHeaders());
        return restTemplate.exchange(url, method, retryRequest, responseType);
      }
      throw e;
    }
  }

  private <T> ResponseEntity<T> exchangeWithRetry(
      String url, HttpMethod method, ParameterizedTypeReference<T> responseType) {
    try {
      HttpEntity<Void> request = new HttpEntity<>(createAuthHeaders());
      return restTemplate.exchange(url, method, request, responseType);
    } catch (HttpClientErrorException e) {
      if (isUnauthorizedOrForbidden(e)) {
        logger.warn(TOKEN_REFRESH_AND_SINGLE_RETRY, e.getStatusCode().value(), method, url);
        authService.refreshToken();
        HttpEntity<Void> retryRequest = new HttpEntity<>(createAuthHeaders());
        return restTemplate.exchange(url, method, retryRequest, responseType);
      }
      throw e;
    }
  }

  public List<DeviceDto> getAllDevices() {
    try {
      logger.debug("Requesting all devices from API Service");
      String url = baseUrl + "/api/devices";

      ResponseEntity<List<DeviceDto>> response =
          exchangeWithRetry(url, HttpMethod.GET, new ParameterizedTypeReference<>() {});

      List<DeviceDto> devices = response.getBody();
      logger.debug(
          "Successfully retrieved {} devices from API Service",
          devices != null ? devices.size() : 0);
      return devices != null ? devices : Collections.emptyList();

    } catch (HttpClientErrorException e) {
      logger.error(
          "HTTP error while getting all devices: {} {}", e.getStatusCode(), e.getMessage());
      return Collections.emptyList();
    } catch (Exception e) {
      logger.error("Error while getting all devices from API Service", e);
      return Collections.emptyList();
    }
  }

  public Optional<DeviceDto> getDeviceById(Long deviceId) {
    try {
      logger.debug("Requesting device with ID: {} from API Service", deviceId);
      String url = baseUrl + "/api/devices/" + deviceId;

      ResponseEntity<DeviceDto> response =
          exchangeWithRetry(url, HttpMethod.GET, null, DeviceDto.class);
      DeviceDto device = response.getBody();

      if (device != null) {
        logger.info("Successfully retrieved device: {} from API Service", device.getName());
        return Optional.of(device);
      } else {
        logger.warn("Device with ID {} not found", deviceId);
        return Optional.empty();
      }

    } catch (HttpClientErrorException.NotFound e) {
      logger.warn("Device with ID {} not found in API Service", deviceId);
      return Optional.empty();
    } catch (HttpClientErrorException e) {
      logger.error(
          "HTTP error while getting device {}: {} {}", deviceId, e.getStatusCode(), e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      logger.error("Error while getting device {} from API Service", deviceId, e);
      return Optional.empty();
    }
  }

  /**
   * Retrieves the complete system state (SystemSettings + all devices) for a given MQTT prefix.
   * This endpoint is only accessible by SERVICE_USER role.
   *
   * @param mqttPrefix The MQTT prefix of the power monitor device
   * @return Optional containing SystemStateDto if found, empty otherwise
   */
  public Optional<SystemStateDto> getSystemStateByMqttPrefix(String mqttPrefix) {
    try {
      logger.debug("Requesting system state for MQTT prefix: {}", mqttPrefix);
      String url = baseUrl + "/api/devices/by-mqtt-prefix/" + mqttPrefix;

      ResponseEntity<SystemStateDto> response =
          exchangeWithRetry(url, HttpMethod.GET, null, SystemStateDto.class);
      SystemStateDto systemState = response.getBody();

      if (systemState != null) {
        logger.debug(
            "Successfully retrieved system state for MQTT prefix: {} with {} devices",
            mqttPrefix,
            systemState.getDevices() != null ? systemState.getDevices().size() : 0);
        return Optional.of(systemState);
      } else {
        logger.warn("System state not found for MQTT prefix: {}", mqttPrefix);
        return Optional.empty();
      }

    } catch (HttpClientErrorException.NotFound e) {
      logger.warn("System state not found for MQTT prefix: {}", mqttPrefix);
      return Optional.empty();
    } catch (HttpClientErrorException e) {
      logger.error(
          "HTTP error while getting system state for MQTT prefix {}: {} {}",
          mqttPrefix,
          e.getStatusCode(),
          e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      logger.error("Error while getting system state for MQTT prefix: {}", mqttPrefix, e);
      return Optional.empty();
    }
  }

  public void notifyApiOfDeviceUpdate(DeviceStatusUpdateDto statusUpdate) {
    try {
      logger.debug(
          "Notifying sbps-api of status update for device: {}", statusUpdate.getDeviceId());
      String url = baseUrl + "/api/control/internal/device-update";

      exchangeWithRetry(url, HttpMethod.POST, statusUpdate, Void.class);

      logger.debug("Successfully notified sbps-api for device {}", statusUpdate.getDeviceId());

    } catch (HttpClientErrorException e) {
      logger.error("HTTP error while notifying sbps-api: {} {}", e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      logger.error("Error while notifying sbps-api", e);
    }
  }
}
