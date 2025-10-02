package com.yh.sbps.device.integration;

import com.yh.sbps.device.dto.DeviceDto;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class ApiServiceClient {

  private static final Logger logger = LoggerFactory.getLogger(ApiServiceClient.class);

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public ApiServiceClient(@Value("${api.base-url}") String baseUrl) {
    this.restTemplate = new RestTemplate();
    this.baseUrl = baseUrl;
    logger.info("ApiServiceClient initialized with base URL: {}", baseUrl);
  }

  public List<DeviceDto> getAllDevices() {
    try {
      logger.debug("Requesting all devices from API Service");
      String url = baseUrl + "/devices";

      ResponseEntity<List<DeviceDto>> response =
          restTemplate.exchange(
              url,
              org.springframework.http.HttpMethod.GET,
              null,
              new ParameterizedTypeReference<>() {});

      List<DeviceDto> devices = response.getBody();
      logger.info(
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
      String url = baseUrl + "/devices/" + deviceId;

      ResponseEntity<DeviceDto> response = restTemplate.getForEntity(url, DeviceDto.class);
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
}
