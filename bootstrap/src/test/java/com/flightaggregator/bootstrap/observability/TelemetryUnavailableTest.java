package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Review Focus 1: with the Collector down, the application starts, ingests and searches at its
 * normal speed, and only telemetry is lost (spec 6.5: search depends only on PostgreSQL). Tracing
 * is on, as in production, and every OTLP endpoint is on port 1, where nothing listens, so every
 * export fails.
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
      "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces",
      "management.opentelemetry.logging.export.otlp.endpoint=http://127.0.0.1:1/v1/logs",
      "management.otlp.metrics.export.url=http://127.0.0.1:1/v1/metrics"
    })
@ImportTestcontainers(PostgresContainers.class)
@AutoConfigureTracing
@DirtiesContext
class TelemetryUnavailableTest {

  @RegisterExtension static final WireMockExtension PROVIDERS = OneFlightProviders.server();

  @DynamicPropertySource
  static void providerUrls(DynamicPropertyRegistry registry) {
    OneFlightProviders.baseUrls(registry, PROVIDERS);
  }

  @Autowired private IngestionJob job;
  @Autowired private JdbcClient jdbc;
  @Autowired private ApplicationContext context;
  @Autowired private SdkTracerProvider tracer;
  @LocalServerPort private int port;

  @BeforeEach
  void startFromAnEmptyInventoryWithTheProvidersAnswering() {
    PostgresContainers.emptyTheInventory(jdbc);
    // The lock a run keeps for a minute after it, lapsed as between two scheduled runs.
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();
    OneFlightProviders.answer(PROVIDERS);
  }

  @Test
  void servesAndIngestsWithTheCollectorDown() throws Exception {
    // The OTLP exporter is built, so the spans below are really sent to the closed port. By name:
    // the exporter is on the runtime classpath only, not the tests' compiler's.
    assertThat(context.getBeansOfType(SpanExporter.class).values())
        .extracting(exporter -> exporter.getClass().getName())
        .contains("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter");

    job.run();
    // The run's spans are sent now, and fail, while the search is served.
    tracer.forceFlush();

    assertThat(jdbc.sql("SELECT generation FROM inventory_publication").query(Long.class).single())
        .isEqualTo(1);
    // The context's first request also starts its servlet, so the second one is timed.
    assertThat(search().statusCode()).isEqualTo(200);
    long started = System.nanoTime();
    HttpResponse<String> search = search();
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    assertThat(search.statusCode()).isEqualTo(200);
    assertThat(took).isLessThan(Duration.ofSeconds(1));
  }

  private HttpResponse<String> search() throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      return http.send(
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/itineraries?origin=MAD"))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
