package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §6.10: the console's ECS JSON lines of an ingestion run carry the run's trace and span ids,
 * beside its correlation id, so that {@code docker compose logs} leads to the trace too. Tracing is
 * on, as in production; the providers are {@link OneFlightProviders}.
 *
 * <p>No other class shares its context, so it is closed after the class, and its connections do not
 * count against the shared PostgreSQL's limit.
 */
@SpringBootTest(
    properties = {
      "providers.alpha.api-key=alpha-key",
      "providers.beta.api-key=beta-key",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers(PostgresContainers.class)
@AutoConfigureTracing
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsTest {

  @RegisterExtension static final WireMockExtension PROVIDERS = OneFlightProviders.server();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void providerUrls(DynamicPropertyRegistry registry) {
    OneFlightProviders.baseUrls(registry, PROVIDERS);
  }

  @Autowired private IngestionJob job;
  @Autowired private JdbcClient jdbc;

  /**
   * Every line the application's loggers write during a run holds the run's trace id, a span id and
   * the run's correlation id, under their ECS names.
   */
  @Test
  void everyLineOfARunCarriesItsTraceSpanAndCorrelationIds(CapturedOutput output) {
    PostgresContainers.emptyTheInventory(jdbc);
    OneFlightProviders.answer(PROVIDERS);
    int before = output.getOut().length();

    job.run();

    List<JsonNode> lines = applicationLines(output.getOut().substring(before));
    assertThat(lines)
        .extracting(line -> line.at("/message").asString())
        .contains("Ingestion run ended");
    assertThat(lines)
        .allSatisfy(
            line -> {
              assertThat(line.path("trace.id").asString()).as("trace.id in %s", line).hasSize(32);
              assertThat(line.path("span.id").asString()).as("span.id in %s", line).hasSize(16);
              assertThat(line.at("/correlation/id").asString())
                  .as("correlation.id in %s", line)
                  .isNotBlank();
            });
    assertThat(lines)
        .extracting(line -> line.path("trace.id").asString())
        .containsOnly(traceOf(lines));
  }

  /** The JSON lines of the application's own loggers, in the order they were written. */
  private static List<JsonNode> applicationLines(String console) {
    return console
        .lines()
        .filter(line -> line.startsWith("{"))
        .map(JSON::readTree)
        .filter(line -> line.at("/log/logger").asString().startsWith("com.flightaggregator"))
        .toList();
  }

  private static String traceOf(List<JsonNode> lines) {
    return lines.getFirst().path("trace.id").asString();
  }
}
