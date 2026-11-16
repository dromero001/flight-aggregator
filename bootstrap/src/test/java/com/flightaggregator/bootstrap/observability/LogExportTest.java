package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * §6.10 (plan 7, Q2-A): the application exports its log records over OTLP, each with the trace
 * context it was written in, so that Loki links a line to its trace. Tracing is on, as in
 * production; records and spans are kept in memory. The providers are {@link OneFlightProviders}.
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
class LogExportTest {

  @RegisterExtension static final WireMockExtension PROVIDERS = OneFlightProviders.server();

  @DynamicPropertySource
  static void providerUrls(DynamicPropertyRegistry registry) {
    OneFlightProviders.baseUrls(registry, PROVIDERS);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class InMemory {

    @Bean
    InMemoryLogRecordExporter records() {
      return InMemoryLogRecordExporter.create();
    }

    @Bean
    InMemorySpanExporter spans() {
      return InMemorySpanExporter.create();
    }
  }

  @Autowired private IngestionJob job;
  @Autowired private JdbcClient jdbc;
  @Autowired private InMemoryLogRecordExporter records;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private SdkLoggerProvider loggers;
  @Autowired private SdkTracerProvider tracer;
  @LocalServerPort private int port;

  @BeforeEach
  void startFromAnEmptyInventoryWithTheProvidersAnswering() {
    PostgresContainers.emptyTheInventory(jdbc);
    // The lock a run keeps for a minute after it, lapsed as between two scheduled runs.
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();
    OneFlightProviders.answer(PROVIDERS);
    records.reset();
    spans.reset();
  }

  /** The run's report line is exported in the run's trace, with the run's correlation id. */
  @Test
  void exportsARunsLinesWithTheirTraceContext() {
    job.run();

    LogRecordData ended =
        exported().stream()
            .filter(record -> record.getBodyValue().asString().equals("Ingestion run ended"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No record of the run's report line"));
    assertThat(ended.getSpanContext().getTraceId()).isEqualTo(runTrace());
    assertThat(ended.getAttributes().get(AttributeKey.stringKey(CorrelationId.MDC_KEY)))
        .isNotBlank();
  }

  /**
   * A line's key-value pairs travel as the record's attributes, so a provider that failed is still
   * named with its reason: Beta, which sells nothing here, is kept with {@code EMPTY_DOWNLOAD}.
   */
  @Test
  void exportsTheKeyValuePairsOfALine() {
    job.run();

    assertThat(exported())
        .filteredOn(
            record ->
                record.getBodyValue().asString().startsWith("Kept the last good offers")
                    && "BETA"
                        .equals(record.getAttributes().get(AttributeKey.stringKey("provider"))))
        .extracting(record -> record.getAttributes().get(AttributeKey.stringKey("reason")))
        .containsExactly("EMPTY_DOWNLOAD");
  }

  /**
   * Telemetry now leaves the process (Review Focus 5): no record of a run or of an admin call holds
   * a provider's key or the caller's bearer token, in its body or in any attribute.
   */
  @Test
  void exportsNoSecretInAnyLogRecord() throws Exception {
    String token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.c2lnbmF0dXJl";
    job.run();
    try (HttpClient http = HttpClient.newHttpClient()) {
      http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/status"))
              .header("Authorization", "Bearer " + token)
              .build(),
          HttpResponse.BodyHandlers.discarding());
    }

    List<LogRecordData> exported = exported();
    assertThat(exported).isNotEmpty();
    assertThat(exported)
        .flatExtracting(LogExportTest::texts)
        .allSatisfy(
            text -> assertThat(text).doesNotContain("alpha-key", "beta-key", "gamma-key", token));
  }

  /** The record's body and every attribute value, as text. */
  private static List<String> texts(LogRecordData record) {
    return Stream.concat(
            Stream.of(record.getBodyValue().asString()),
            record.getAttributes().asMap().values().stream().map(String::valueOf))
        .toList();
  }

  private List<LogRecordData> exported() {
    loggers.forceFlush().join(10, TimeUnit.SECONDS);
    return records.getFinishedLogRecordItems();
  }

  /** The trace id of the run's span. */
  private String runTrace() {
    tracer.forceFlush().join(10, TimeUnit.SECONDS);
    return spans.getFinishedSpanItems().stream()
        .filter(span -> span.getName().equals(IngestionJob.RUN_OBSERVATION))
        .map(SpanData::getTraceId)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No span named " + IngestionJob.RUN_OBSERVATION));
  }
}
