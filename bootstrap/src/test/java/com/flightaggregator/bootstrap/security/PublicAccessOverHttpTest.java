package com.flightaggregator.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpStatus;

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

  /** Until the endpoint exists the honest answer is 404; it must not be turned into a 401. */
  @Test
  void anAnonymousSearchIsNeverAskedForAToken() {
    assertThat(http.getForEntity("/api/itineraries", String.class).getStatusCode())
        .isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }

  /** Permitting the error page must not open the admin side. */
  @Test
  void anAnonymousAdminCallIsStillAskedForAToken() {
    assertThat(http.getForEntity("/actuator/metrics", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
