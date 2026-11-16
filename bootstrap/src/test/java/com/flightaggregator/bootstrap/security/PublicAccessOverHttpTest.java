package com.flightaggregator.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The public paths on a real server, where an error goes through the servlet container's error
 * page: MockMvc never makes that round trip, so {@code SecurityTest} cannot see it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "providers.alpha.base-url=http://localhost:1/",
      "providers.alpha.api-key=alpha-key",
      "providers.beta.base-url=http://localhost:1/",
      "providers.beta.api-key=beta-key",
      "providers.gamma.base-url=http://localhost:1/",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers(PostgresContainers.class)
@AutoConfigureTestRestTemplate
class PublicAccessOverHttpTest {

  @Autowired private TestRestTemplate http;

  /** The search is public (§6.6): answered without a token, from the inventory as it stands. */
  @Test
  void anAnonymousSearchIsAnswered() {
    assertThat(http.getForEntity("/api/itineraries", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  /** Swagger UI (plan 5's extra E2) opens without a token, to browse and try the API. */
  @Test
  void swaggerUiAnswersAnAnonymousClient() {
    ResponseEntity<String> page = http.getForEntity("/swagger-ui.html", String.class);

    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page.getBody()).contains("<title>Swagger UI</title>");
  }

  /** The page reads its configuration, which names the contract, before the contract itself. */
  @Test
  void swaggerUiConfigurationAnswersAnAnonymousClient() {
    ResponseEntity<String> config = http.getForEntity("/v3/api-docs/swagger-config", String.class);

    assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(config.getBody()).contains("\"url\":\"/v3/api-docs\"");
  }

  /**
   * Opening Swagger UI opens nothing else: the admin side and the contract's other forms still ask
   * for a token, so the UI's admin operations answer 401 without one.
   */
  @ParameterizedTest
  @ValueSource(strings = {"/api/admin/status", "/v3/api-docs.yaml"})
  void swaggerUiOpensNothingElse(String path) {
    assertThat(http.getForEntity(path, String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  /** Permitting the error page must not open the admin side. */
  @Test
  void anAnonymousAdminCallIsStillAskedForAToken() {
    assertThat(http.getForEntity("/api/admin/ingestions", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
