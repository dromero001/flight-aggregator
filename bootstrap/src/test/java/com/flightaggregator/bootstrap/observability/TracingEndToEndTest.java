package com.flightaggregator.bootstrap.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * §6.10: one ingestion run, search or re-price is one trace, carried to the providers as W3C {@code
 * traceparent}. Tracing is on, as in production, and the spans are kept in memory. One WireMock
 * server answers for the three providers: Alpha sells IB3170 Madrid-London, on its listing and on
 * its quote; Beta and Gamma sell nothing.
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
class TracingEndToEndTest {

  /** Reset before each test by the extension itself. */
  @RegisterExtension
  static final WireMockExtension PROVIDERS =
      WireMockExtension.newInstance()
          .options(WireMockConfiguration.options().dynamicPort())
          .build();

  /** Three days ahead, so that the leg is still sellable whenever the tests run. */
  private static final LocalDate DAY = LocalDate.now(ZoneOffset.UTC).plusDays(3);

  @DynamicPropertySource
  static void providerUrls(DynamicPropertyRegistry registry) {
    for (Provider provider : Provider.values()) {
      registry.add(
          "providers." + provider.name().toLowerCase(Locale.ROOT) + ".base-url",
          PROVIDERS::baseUrl);
    }
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

  @BeforeEach
  void startFromAnEmptyInventoryWithTheProvidersAnswering() {
    PostgresContainers.emptyTheInventory(jdbc);
    // The lock a run keeps for a minute after it, lapsed as between two scheduled runs.
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();
    PROVIDERS.stubFor(get(urlPathMatching("/alpha/.*")).willReturn(ok(alphaPage())));
    PROVIDERS.stubFor(
        get(urlPathMatching("/beta/.*"))
            .willReturn(
                ok(
                    "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments></Segments>"
                        + "</SegmentsResponse>")));
    PROVIDERS.stubFor(
        get(urlPathMatching("/gamma/.*"))
            .willReturn(ok("airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n")));
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

  /** IB3170 Madrid-London, the one record of a listing and of a quote. */
  private static String alphaPage() {
    return "{\"legs\":[{\"carrier\":\"IB\",\"flightNumber\":3170,\"origin\":\"MAD\","
        + "\"destination\":\"LHR\",\"departureTime\":\""
        + DAY
        + "T08:15:00+01:00\",\"arrivalTime\":\""
        + DAY
        + "T09:30:00+00:00\",\"price\":\"129.90\"}],\"nextCursor\":null}";
  }
}
