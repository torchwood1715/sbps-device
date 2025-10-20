package com.yh.sbps.device.integration;

import com.yh.sbps.device.dto.LoginRequestDto;
import com.yh.sbps.device.dto.LoginResponseDto;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AuthService {

  private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

  private final RestTemplate restTemplate;
  private final String baseUrl;
  private final String serviceUserEmail;
  private final String serviceUserPassword;

  private String authToken;

  public AuthService(
      @Value("${api.base-url}") String baseUrl,
      @Value("${service-user.email}") String serviceUserEmail,
      @Value("${service-user.password}") String serviceUserPassword) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(5));
    factory.setReadTimeout((int) TimeUnit.SECONDS.toMillis(5));
    this.restTemplate = new RestTemplate(factory);
    this.baseUrl = baseUrl;
    this.serviceUserEmail = serviceUserEmail;
    this.serviceUserPassword = serviceUserPassword;
    logger.info("AuthService initialized with base URL: {}", baseUrl);
  }

  /**
   * Authenticates with the sbps-api and gets a JWT token for the SERVICE_USER.
   *
   * @return JWT token
   */
  private String login() {

    int maxRetries = 3;
    long retryDelayMillis = TimeUnit.SECONDS.toMillis(20);

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        logger.info("Attempting login (attempt {}/{}) to {}", attempt, maxRetries, baseUrl);
        String url = baseUrl + "/api/auth/login";

        LoginRequestDto loginRequest = new LoginRequestDto(serviceUserEmail, serviceUserPassword);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<LoginRequestDto> request = new HttpEntity<>(loginRequest, headers);

        ResponseEntity<LoginResponseDto> response =
            restTemplate.postForEntity(url, request, LoginResponseDto.class);

        if (response.getBody() != null && response.getBody().getToken() != null) {
          logger.info("Successfully authenticated as SERVICE_USER on attempt {}", attempt);
          return response.getBody().getToken();
        } else {
          logger.error("Login response body or token is null on attempt {}", attempt);
        }
        try {
          Thread.sleep(retryDelayMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          logger.error("Retry delay interrupted", ie);
          return null;
        }
      } catch (Exception e) {
        logger.error("Unexpected error during login attempt {}", attempt, e);
        return null;
      }
    }
    return null;
  }

  /**
   * Returns the stored JWT token. If the token is null, it attempts to log in first.
   *
   * @return JWT token or null if authentication fails
   */
  public String getAuthToken() {
    if (authToken == null) {
      logger.debug("Auth token is null, attempting to login");
      authToken = login();
    }
    return authToken;
  }

  /**
   * Forces a re-authentication by clearing the current token and logging in again. This can be used
   * when the token expires or becomes invalid.
   */
  public void refreshToken() {
    logger.info("Refreshing authentication token");
    authToken = null;
    authToken = login();
  }
}
