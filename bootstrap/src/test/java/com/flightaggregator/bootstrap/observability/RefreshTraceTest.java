package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flightaggregator.adapter.persistence.support.ValkeyImage;
import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.redis.testcontainers.RedisContainer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.utility.DockerImageName;

/**
 * §6.10: a refresh that a search asks for (§6.4) continues the search's trace, though it runs in
 * the background after the search has answered. Valkey runs, since the search asks for a refresh
 * only once it holds the origin's claim there, and every origin is stale at once.
 *
 * <p>No other class shares its context, so it is closed after the class, and its connections do not
 * count against the shared PostgreSQL's limit.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "providers.alpha.api-key=alpha-key",
      "providers.beta.api-key=beta-key",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false",
      "search.freshness-threshold=PT0.001S"
    })
@ImportTestcontainers({PostgresContainers.class, RefreshTraceTest.Valkey.class})
@AutoConfigureTracing
@DirtiesContext
class RefreshTraceTest {

  @RegisterExtension static final WireMockExtension PROVIDERS = OneFlightProviders.server();

  /** The trace of the caller of the search, W3C's example. */
  private static final String CALLERS_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

  interface Valkey {

    @ServiceConnection(name = "redis")
    RedisContainer VALKEY = new RedisContainer(DockerImageName.parse(ValkeyImage.NAME));
  }

  @DynamicPropertySource
  static void providerUrls(DynamicPropertyRegistry registry) {
    OneFlightProviders.baseUrls(registry, PROVIDERS);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Spans {

    @Bean
    InMemorySpanExporter spans() {
      return InMemorySpanExporter.create();
    }
  }

  @Autowired private IngestionJob job;
  @Autowired private JdbcClient jdbc;
  @Autowired private StringRedisTemplate valkey;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private SdkTracerProvider tracer;
  @LocalServerPort private int port;

  @BeforeEach
  void startFromAPublishedInventoryWithNoRefreshClaimed() {
    PostgresContainers.emptyTheInventory(jdbc);
    // The lock a run keeps for a minute after it, lapsed as between two scheduled runs.
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();
    // So that the refresh row this test waits for is the one its own refresh writes.
    jdbc.sql("DELETE FROM shedlock WHERE name = 'refresh'").update();
    valkey.execute(
        connection -> {
          connection.serverCommands().flushAll();
          return null;
        },
        true);
    OneFlightProviders.answer(PROVIDERS);
    job.run();
    PROVIDERS.resetRequests();
    tracer.forceFlush().join(10, TimeUnit.SECONDS);
    spans.reset();
  }

  /** Its provider requests and its Valkey mark of the origin are in the search's trace. */
  @Test
  void aRefreshContinuesTheTraceOfTheSearchThatAskedForIt() throws Exception {
    HttpResponse<String> search;
    try (HttpClient http = HttpClient.newHttpClient()) {
      search =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/itineraries?origin=MAD"))
                  .header("traceparent", "00-" + CALLERS_TRACE + "-00f067aa0ba902b7-01")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    }
    assertThat(search.statusCode()).isEqualTo(200);

    // The refresh holds ShedLock's refresh row while it runs and gives it back when it ends.
    await().atMost(Duration.ofSeconds(30)).until(this::theRefreshEnded);
    List<String> sent = traceparents();
    assertThat(sent).hasSize(3).doesNotContainNull();
    assertThat(sent).containsOnly(CALLERS_TRACE);
    assertThat(exported())
        .filteredOn(span -> span.getName().equals("set"))
        .isNotEmpty()
        .extracting(SpanData::getTraceId)
        .containsOnly(CALLERS_TRACE);
  }

  private boolean theRefreshEnded() {
    return jdbc.sql("SELECT count(*) FROM shedlock WHERE name = 'refresh' AND lock_until <= now()")
            .query(Long.class)
            .single()
        == 1;
  }

  /** The trace id of every provider request, read from its {@code traceparent}; null if none. */
  private static List<String> traceparents() {
    return OneFlightProviders.traceIds(PROVIDERS);
  }

  private List<SpanData> exported() {
    tracer.forceFlush().join(10, TimeUnit.SECONDS);
    return spans.getFinishedSpanItems();
  }
}
