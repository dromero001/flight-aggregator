package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * §6.10: one ingestion run, search or re-price is one trace, carried to the providers as W3C {@code
 * traceparent}. Tracing is on, as in production, and the spans are kept in memory. The providers
 * are {@link OneFlightProviders}: a run publishes IB3170 Madrid-London, and its re-price asks Alpha
 * for one quote.
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
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers(PostgresContainers.class)
@AutoConfigureTracing
@DirtiesContext
class TracingEndToEndTest {

  @RegisterExtension static final WireMockExtension PROVIDERS = OneFlightProviders.server();

  /** The trace of a caller that sends its own {@code traceparent}, W3C's example. */
  private static final String CALLERS_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

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
  @Autowired private InMemorySpanExporter spans;
  @Autowired private SdkTracerProvider tracer;
  @Autowired private ApplicationContext context;
  @Autowired private MeterRegistry meters;
  @LocalServerPort private int port;

  @BeforeEach
  void startFromAnEmptyInventoryWithTheProvidersAnswering() {
    PostgresContainers.emptyTheInventory(jdbc);
    // The lock a run keeps for a minute after it, lapsed as between two scheduled runs.
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();
    OneFlightProviders.answer(PROVIDERS);
    spans.reset();
  }

  /** The run's span and every provider request share one trace id. */
  @Test
  void anIngestionRunIsOneTrace() {
    job.run();

    List<String> sent = traceparents();
    assertThat(sent).hasSize(3).doesNotContainNull();
    assertThat(sent).containsOnly(traceOf("ingestion.run"));
  }

  /** "Run ingestion now" runs on a thread of its own, which the run's span starts too. */
  @Test
  void aManualRunIsOneTraceToo() {
    assertThat(job.launch()).isTrue();

    // launch returns once the run holds its lock; its span is exported once it ends.
    String run =
        await()
            .atMost(Duration.ofSeconds(30))
            .ignoreExceptions()
            .until(() -> traceOf("ingestion.run"), Objects::nonNull);
    List<String> sent = traceparents();
    assertThat(sent).hasSize(3).doesNotContainNull();
    assertThat(sent).containsOnly(run);
  }

  /** A search called with a W3C {@code traceparent} is served within the caller's trace. */
  @Test
  void aSearchContinuesTheCallersTrace() throws Exception {
    HttpResponse<String> search =
        send(
            HttpRequest.newBuilder(uri("/api/itineraries?origin=MAD"))
                .header("traceparent", "00-" + CALLERS_TRACE + "-00f067aa0ba902b7-01")
                .build());

    assertThat(search.statusCode()).isEqualTo(200);
    assertThat(exported())
        .filteredOn(span -> span.getKind() == SpanKind.SERVER)
        .extracting(SpanData::getTraceId)
        .containsExactly(CALLERS_TRACE);
  }

  /** The re-price's server span and the quote it asks the provider share one trace. */
  @Test
  void aRepriceIsOneTraceWithItsQuotes() throws Exception {
    job.run();
    PROVIDERS.resetRequests();
    spans.reset();

    HttpResponse<String> reprice =
        send(
            HttpRequest.newBuilder(uri("/api/itineraries/" + theOneItinerary() + "/reprice"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());

    assertThat(reprice.statusCode()).isEqualTo(200);
    String server =
        exported().stream()
            .filter(span -> span.getKind() == SpanKind.SERVER)
            .map(SpanData::getTraceId)
            .findFirst()
            .orElseThrow();
    List<String> sent = traceparents();
    assertThat(sent).hasSize(1).doesNotContainNull();
    assertThat(sent).containsOnly(server);
  }

  /**
   * Telemetry now leaves the process (Review Focus 5): no span of a run or of an admin call holds a
   * provider's key or the caller's bearer token.
   */
  @Test
  void exportsNoSecretInAnySpan() throws Exception {
    String token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.c2lnbmF0dXJl";
    job.run();
    send(
        HttpRequest.newBuilder(uri("/api/admin/status"))
            .header("Authorization", "Bearer " + token)
            .build());

    List<SpanData> exported = exported();
    assertThat(exported).isNotEmpty();
    assertThat(exported)
        .flatExtracting(TracingEndToEndTest::attributeValues)
        .allSatisfy(
            value -> assertThat(value).doesNotContain("alpha-key", "beta-key", "gamma-key", token));
  }

  /**
   * Metrics take one path, Prometheus's scrape (§6.6): the OpenTelemetry starter's OTLP meter
   * registry is not built, so nothing pushes them. Metrics export is on in this test, as in
   * production.
   */
  @Test
  void pushesNoMetrics() {
    // By name: the starter brings the OTLP registry at runtime only, not to the tests' compiler.
    assertThat(context.getBeansOfType(MeterRegistry.class).values())
        .extracting(registry -> registry.getClass().getName())
        .isNotEmpty()
        .doesNotContain("io.micrometer.registry.otlp.OtlpMeterRegistry");
  }

  /**
   * The run's observation makes its span and no meter of its own: {@code ingestion.duration}
   * already times every run (§6.4), and {@code ingestion.runs} counts them.
   */
  @Test
  void timesARunOnlyAsIngestionDuration() {
    job.run();

    assertThat(meters.getMeters())
        .extracting(meter -> meter.getId().getName())
        .contains("ingestion.duration", "ingestion.runs")
        .doesNotContain("ingestion.run", "ingestion.run.active");
  }

  /** Every attribute value of the span and of its events, as text. */
  private static List<String> attributeValues(SpanData span) {
    return Stream.concat(
            Stream.of(span.getAttributes()),
            span.getEvents().stream().map(EventData::getAttributes))
        .flatMap(attributes -> attributes.asMap().values().stream())
        .map(String::valueOf)
        .toList();
  }

  /** The trace id of every provider request, read from its {@code traceparent}; null if none. */
  private static List<String> traceparents() {
    return PROVIDERS.getAllServeEvents().stream()
        .map(ServeEvent::getRequest)
        .map(request -> request.getHeader("traceparent"))
        .map(header -> header == null ? null : header.split("-")[1])
        .toList();
  }

  /** The trace id of the one exported span of that name. */
  private String traceOf(String spanName) {
    return exported().stream()
        .filter(span -> span.getName().equals(spanName))
        .map(SpanData::getTraceId)
        .reduce(
            (first, second) -> {
              throw new AssertionError("More than one span named " + spanName);
            })
        .orElseThrow(() -> new AssertionError("No span named " + spanName));
  }

  private List<SpanData> exported() {
    tracer.forceFlush().join(10, TimeUnit.SECONDS);
    return spans.getFinishedSpanItems();
  }

  /** The id of the one itinerary the run published: IB3170 Madrid-London. */
  private String theOneItinerary() {
    String slot =
        jdbc.sql("SELECT active_slot FROM inventory_publication").query(String.class).single();
    return jdbc.sql("SELECT id FROM itinerary_" + slot).query(String.class).single();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  /** Sent by a plain JDK client, which adds no trace header of its own. */
  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
