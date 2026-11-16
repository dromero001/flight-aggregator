package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresImage;
import com.flightaggregator.adapter.persistence.support.ValkeyImage;
import com.redis.testcontainers.RedisContainer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Q8-A's one cause of {@code DOWN}, on a real server: with PostgreSQL stopped, the API port's
 * {@code /health} (plan 7 Task 2) answers Boot's 503 {@code DOWN} within the 5 s compose gives its
 * health check (the {@code app} service's {@code timeout}), so the orchestrator reads a status, not
 * a timeout. Right after the stop, a probe finds pooled connections that fail at once; the slow
 * case is the one after, when the pool holds none and every read waits for a new one, so the probes
 * are timed from then on. The test stops a PostgreSQL of its own, never the one the other test
 * classes share, and closes its context afterwards.
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
@ImportTestcontainers(HealthWithoutPostgresTest.Containers.class)
@DirtiesContext
class HealthWithoutPostgresTest {

  /** The {@code timeout} of the {@code app} service's health check in {@code compose.yaml}. */
  private static final Duration COMPOSE_HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);

  interface Containers {

    @ServiceConnection(name = "postgres")
    PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(
            DockerImageName.parse(PostgresImage.NAME).asCompatibleSubstituteFor("postgres"));

    @ServiceConnection(name = "redis")
    RedisContainer VALKEY = new RedisContainer(DockerImageName.parse(ValkeyImage.NAME));
  }

  /** Enough probes to empty a pool of Hikari's default ten connections, two reads per probe. */
  private static final int MOST_PROBES_TO_EMPTY_THE_POOL = 10;

  @Autowired private HikariDataSource dataSource;
  @LocalServerPort private int port;

  @Test
  void answers503DownWithinTheComposeHealthCheckTimeout() throws Exception {
    assertThat(health().statusCode()).isEqualTo(200);

    DockerClientFactory.instance()
        .client()
        .stopContainerCmd(Containers.POSTGRES.getContainerId())
        .exec();

    for (int probe = 1; dataSource.getHikariPoolMXBean().getTotalConnections() > 0; probe++) {
      assertThat(probe)
          .as("probes before the pool is empty")
          .isLessThan(MOST_PROBES_TO_EMPTY_THE_POOL);
      assertThat(health().statusCode()).isEqualTo(503);
    }
    for (int probe = 1; probe <= 3; probe++) {
      long started = System.nanoTime();
      HttpResponse<String> health = health();
      Duration took = Duration.ofNanos(System.nanoTime() - started);

      assertThat(health.statusCode()).as("probe %d", probe).isEqualTo(503);
      assertThat(health.body()).as("probe %d", probe).contains("\"status\":\"DOWN\"");
      assertThat(took).as("probe %d", probe).isLessThan(COMPOSE_HEALTH_CHECK_TIMEOUT);
    }
  }

  private HttpResponse<String> health() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
              .timeout(Duration.ofMinutes(2))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
