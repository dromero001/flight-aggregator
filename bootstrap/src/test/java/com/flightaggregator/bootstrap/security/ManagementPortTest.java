package com.flightaggregator.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.AggregatorTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec §6.6 (the user's decision of 2026-11-15): Actuator lives on a management port that only
 * compose's network reaches, open to Prometheus with no token, and the API port serves no metrics.
 * Real sockets, because MockMvc does not see the management port; with a random server port, Boot
 * gives the management server a random port too, since {@code application.yaml} sets a different
 * one.
 */
@AggregatorTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementPortTest {

  /** The bearer token that {@link AdminTokens} reads as one with the ADMIN realm role. */
  private static final String ADMIN_TOKEN = "admin-token";

  @LocalServerPort private int apiPort;
  @LocalManagementPort private int managementPort;
  @Autowired private JsonMapper json;

  @Test
  void scrapesWithoutATokenOnTheManagementPort() throws Exception {
    HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

    assertThat(scrape.statusCode()).isEqualTo(200);
    assertThat(scrape.body()).contains("ingestion_consecutive_failures");
  }

  /** Review Focus 3: not even an administrator's token reaches a metrics endpoint there. */
  @ParameterizedTest
  @ValueSource(strings = {"/actuator/prometheus", "/actuator/metrics"})
  void neverServesMetricsOnTheApiPort(String path) throws Exception {
    assertThat(send(request(apiPort, path).header("Authorization", "Bearer " + ADMIN_TOKEN)))
        .extracting(HttpResponse::statusCode)
        .isEqualTo(404);
  }

  /**
   * Plan 5's public health, now at {@code /health} on the API port (the user's decision of
   * 2026-11-16): its status for anyone, its components for the ADMIN role. No Valkey runs here, so
   * the status is DEGRADED, which still answers 200 (Q8-A).
   */
  @Test
  void keepsTheApiPortsHealthAsPlanFiveLeftIt() throws Exception {
    HttpResponse<String> anonymous = get(apiPort, "/health");
    HttpResponse<String> admin =
        send(request(apiPort, "/health").header("Authorization", "Bearer " + ADMIN_TOKEN));

    assertThat(anonymous.statusCode()).isEqualTo(200);
    assertThat(json.readTree(anonymous.body()).path("status").asString()).isEqualTo("DEGRADED");
    assertThat(json.readTree(anonymous.body()).has("components")).isFalse();
    assertThat(admin.statusCode()).isEqualTo(200);
    assertThat(json.readTree(admin.body()).path("components").path("db").path("status").asString())
        .isEqualTo("UP");
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return send(request(port, path));
  }

  private static HttpRequest.Builder request(int port, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /**
   * Replaces Boot's decoder, which would fetch Keycloak's keys: {@link #ADMIN_TOKEN} becomes a JWT
   * with the ADMIN realm role, mapped to an authority by Boot's own settings, as Keycloak's would
   * be ({@code SecurityTest} checks the real decoder). Any other token is rejected.
   */
  @TestConfiguration
  static class AdminTokens {

    @Bean
    JwtDecoder adminTokenDecoder() {
      return token -> {
        if (!ADMIN_TOKEN.equals(token)) {
          throw new BadJwtException("Not the test's admin token");
        }
        return Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject("admin")
            .claim("realm_access", Map.of("roles", List.of("ADMIN")))
            .build();
      };
    }
  }
}
