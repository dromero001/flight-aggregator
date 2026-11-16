package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderRun;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.flightaggregator.bootstrap.observability.InventoryHealthIndicator;
import com.flightaggregator.bootstrap.refresh.BackgroundRefresh;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AggregatorTest
@ExtendWith(OutputCaptureExtension.class)
class FlightAggregatorApplicationTest {

  @Autowired private HealthEndpoint health;
  @Autowired private StatusAggregator statusOrder;
  @Autowired private HttpCodeStatusMapper statusCodes;
  @Autowired private Flyway flyway;
  @Autowired private OfferStore offers;
  @Autowired private IngestionStatusStore statuses;
  @Autowired private ItineraryRepository itineraries;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private IngestInventory ingestion;
  @Autowired private IngestionJob job;
  @Autowired private DiscardedRecords discarded;
  @Autowired private JsonMapper json;
  @Autowired private ConfigurableEnvironment environment;
  @Autowired private List<RefreshScheduler> refreshSchedulers;

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor tasks;

  /** The database is shared with the other test classes, whose runs leave offers behind. */
  @BeforeEach
  void startFromAnEmptyInventory() {
    PostgresContainers.emptyTheInventory(jdbc);
  }

  /**
   * Q8-A: DEGRADED is worse than UP and better than DOWN, so PostgreSQL down still makes the
   * service DOWN, and it answers 200, so the orchestrator does not restart the API for it.
   */
  @Test
  void ranksDegradedBetweenDownAndUpAndAnswersIt200() {
    assertThat(statusOrder.getAggregateStatus(Status.DOWN, InventoryHealthIndicator.DEGRADED))
        .isEqualTo(Status.DOWN);
    assertThat(statusOrder.getAggregateStatus(Status.UP, InventoryHealthIndicator.DEGRADED))
        .isEqualTo(InventoryHealthIndicator.DEGRADED);
    assertThat(statusCodes.getStatusCode(InventoryHealthIndicator.DEGRADED)).isEqualTo(200);
    assertThat(statusCodes.getStatusCode(Status.DOWN)).isEqualTo(503);
  }

  @Test
  void startsAgainstPostgresAndMigrates() {
    // No Valkey runs in this test, so the service as a whole is DEGRADED (Q8-A); PostgreSQL is up.
    assertThat(health.healthForPath("db"))
        .extracting(HealthDescriptor::getStatus)
        .isEqualTo(Status.UP);
    assertThat(flyway.info().applied())
        .extracting(migration -> migration.getVersion().getVersion(), MigrationInfo::getState)
        .containsExactly(
            tuple("1", MigrationState.SUCCESS),
            tuple("2", MigrationState.SUCCESS),
            tuple("3", MigrationState.SUCCESS),
            tuple("4", MigrationState.SUCCESS),
            tuple("5", MigrationState.SUCCESS),
            tuple("6", MigrationState.SUCCESS),
            tuple("7", MigrationState.SUCCESS));
  }

  /**
   * The application holds one refresh scheduler, the background refresh, which replaced plan 5 Task
   * 6's provisional one. A second one marked {@code Primary} would let startup go on, so this
   * counts them (the user's decision of 2026-11-15: the provisional scheduler cannot linger).
   */
  @Test
  void holdsOneRefreshScheduler() {
    assertThat(refreshSchedulers).singleElement().isInstanceOf(BackgroundRefresh.class);
  }

  /**
   * The stores write in Boot's {@code TransactionTemplate}'s transaction: each is built from Boot's
   * one transaction manager and takes its connections from it, so a rollback leaves none of their
   * writes behind.
   */
  @Test
  void writesEveryStoreInTheTransactionOfBootsDataSource() {
    long generation = generation();
    OffsetDateTime departure = OffsetDateTime.parse("2026-11-20T08:15:00+01:00");
    Leg leg =
        new Leg(
            new Carrier("IB"),
            3170,
            new Airport("MAD"),
            new Airport("LHR"),
            departure,
            departure.plusHours(2),
            Money.euros("129.90"),
            Provider.ALPHA,
            Instant.parse("2026-11-15T10:00:00Z"));

    transaction.executeWithoutResult(
        status -> {
          offers.replace(Provider.ALPHA, List.of(leg));
          Publication publication = itineraries.replaceInventory(Instant.now(), sink -> {});
          statuses.record(
              List.of(
                  new ProviderRun(Provider.ALPHA, Optional.empty(), 1, Map.of(), Optional.empty())),
              Instant.now());
          assertThat(offers.offers(Provider.ALPHA)).extracting(Leg::key).containsExactly(leg.key());
          assertThat(publication.generation()).isEqualTo(generation + 1);
          assertThat(statuses.latest()).hasSize(1);
          status.setRollbackOnly();
        });

    assertThat(offers.offers(Provider.ALPHA)).isEmpty();
    assertThat(generation()).isEqualTo(generation);
    assertThat(statuses.latest()).isEmpty();
  }

  /**
   * The service runs with the three adapters and the stores. No provider answers in this test, so
   * each keeps its (empty) offers and nothing is published.
   */
  @Test
  void ingestsFromTheThreeProviders() {
    IngestionReport report = ingestion.ingest();

    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.NOTHING_TO_PUBLISH);
    assertThat(report.providers())
        .extracting(ProviderReport::provider, ProviderReport::status, ProviderReport::failure)
        .containsExactly(
            tuple(
                Provider.ALPHA, ProviderReport.Status.KEPT, Optional.of(FailureReason.HTTP_ERROR)),
            tuple(Provider.BETA, ProviderReport.Status.KEPT, Optional.of(FailureReason.HTTP_ERROR)),
            tuple(
                Provider.GAMMA, ProviderReport.Status.KEPT, Optional.of(FailureReason.HTTP_ERROR)));
    assertThat(statuses.latest())
        .extracting(ProviderRun::provider, ProviderRun::failure)
        .containsExactly(
            tuple(Provider.ALPHA, Optional.of(FailureReason.HTTP_ERROR)),
            tuple(Provider.BETA, Optional.of(FailureReason.HTTP_ERROR)),
            tuple(Provider.GAMMA, Optional.of(FailureReason.HTTP_ERROR)));
  }

  /** The console is Boot's structured JSON (§6.10), so the record's fields are fields of it. */
  @Test
  void logsADiscardedRecordAsJsonFields(CapturedOutput output) {
    discarded.discarded(
        Provider.BETA, new DiscardedRecord(DiscardReason.SHAPE, "<Segment>\"IB\"</Segment>"));

    JsonNode line =
        output
            .getOut()
            .lines()
            .filter(text -> text.contains("Discarded a provider record"))
            .map(json::readTree)
            .reduce((first, last) -> last)
            .orElseThrow();
    assertThat(line.path("provider").asString()).isEqualTo("BETA");
    assertThat(line.path("reason").asString()).isEqualTo("SHAPE");
    assertThat(line.path("excerpt").asString()).isEqualTo("<Segment>\"IB\"</Segment>");
  }

  /**
   * The run id reaches the console's JSON as {@code correlation.id}, nested as ECS nests dotted
   * names, on each line of the run: here the three kept providers' and the run's own.
   */
  @Test
  void writesTheRunIdIntoEachJsonLineOfARun(CapturedOutput output) {
    // Another test class's run may still hold the lock for its minute (lockAtLeastFor).
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();

    job.run();

    List<String> runIds =
        output
            .getOut()
            .lines()
            .filter(
                text ->
                    text.contains("Ingestion run ended")
                        || text.contains("Kept the last good offers of a provider that failed"))
            .map(json::readTree)
            .map(line -> line.path("correlation").path("id").asString())
            .toList();
    assertThat(runIds).hasSize(4).doesNotContain("");
    assertThat(Set.copyOf(runIds)).hasSize(1);
  }

  /**
   * A connection whose server stops answering mid-statement fails after two minutes instead of
   * blocking a run for ever, and TCP keepalive notices a peer that is gone.
   */
  @Test
  void connectsWithAReadTimeoutAndTcpKeepAlive() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getNetworkTimeout()).isEqualTo(120_000);
    }
    assertThat(((HikariDataSource) dataSource).getDataSourceProperties())
        .containsEntry("tcpKeepAlive", "true");
  }

  /** Boot's own tasks, and the schedule of Task 10 with them, run on virtual threads (§6.1). */
  @Test
  void runsBootsTasksOnVirtualThreads() throws Exception {
    assertThat(tasks.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
  }

  /**
   * The production values, as the application's own configuration gives them, before this test's
   * settings override any of them (it turns the schedule off). Actuator serves {@code health},
   * {@code metrics} and {@code prometheus} on its own port, 8079 (plan 7, Q4), the one compose's
   * Prometheus scrapes, and nothing else, so that a change of default cannot widen it (spec 6.6).
   */
  @Test
  void configuresTheProductionIngestionAndTheManagementPort() {
    PropertySource<?> application =
        environment.getPropertySources().stream()
            .filter(source -> source.getName().contains("application.yaml"))
            .findFirst()
            .orElseThrow();

    assertThat(application.getProperty("ingestion.max-itineraries")).hasToString("2000000");
    assertThat(application.getProperty("ingestion.interval")).hasToString("PT30M");
    assertThat(application.getProperty("ingestion.schedule.enabled")).hasToString("true");
    assertThat(application.getProperty("management.endpoints.web.exposure.include"))
        .hasToString("health,metrics,prometheus");
    assertThat(application.getProperty("management.server.port")).hasToString("8079");
  }

  private long generation() {
    return jdbc.sql("SELECT generation FROM inventory_publication").query(Long.class).single();
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void failsToStartWithoutAProviderApiKey(Provider provider, CapturedOutput output) {
    String prefix = "providers." + provider.name().toLowerCase(Locale.ROOT);

    Throwable failure = startupFailureWithout(prefix + ".api-key");

    assertThat(failure)
        .hasStackTraceContaining("'" + prefix + "'")
        .hasStackTraceContaining("api-key must be set");
    assertNoKeyIn(failure, output);
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void failsToStartWithoutAProviderBaseUrl(Provider provider, CapturedOutput output) {
    String prefix = "providers." + provider.name().toLowerCase(Locale.ROOT);

    Throwable failure = startupFailureWithout(prefix + ".base-url");

    assertThat(failure)
        .hasStackTraceContaining("'" + prefix + "'")
        .hasStackTraceContaining("base-url must be set");
    assertNoKeyIn(failure, output);
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void failsToStartWithoutAnySettingOfAProvider(Provider provider, CapturedOutput output) {
    String prefix = "providers." + provider.name().toLowerCase(Locale.ROOT);

    Throwable failure = startupFailureWithout(prefix);

    assertThat(failure).hasStackTraceContaining(prefix + ".base-url and " + prefix + ".api-key");
    assertNoKeyIn(failure, output);
  }

  /** Runs the application with every setting but those under a name, and returns why. */
  private static Throwable startupFailureWithout(String name) {
    String[] arguments =
        Stream.concat(
                Stream.of(
                    "spring.datasource.url=" + PostgresContainers.POSTGRES.getJdbcUrl(),
                    "spring.datasource.username=" + PostgresContainers.POSTGRES.getUsername(),
                    "spring.datasource.password=" + PostgresContainers.POSTGRES.getPassword(),
                    "ingestion.schedule.enabled=false"),
                Stream.of(
                        AggregatorTest.ALPHA_URL,
                        AggregatorTest.ALPHA_KEY,
                        AggregatorTest.BETA_URL,
                        AggregatorTest.BETA_KEY,
                        AggregatorTest.GAMMA_URL,
                        AggregatorTest.GAMMA_KEY)
                    .filter(
                        setting ->
                            !setting.startsWith(name + "=") && !setting.startsWith(name + ".")))
            .map(setting -> "--" + setting)
            .toArray(String[]::new);
    Throwable failure =
        catchThrowable(
            () -> SpringApplication.run(FlightAggregatorApplication.class, arguments).close());
    assertThat(failure).as("the application must not start without " + name).isNotNull();
    return failure;
  }

  /** Neither the failure, its causes included, nor anything logged prints a key. */
  private static void assertNoKeyIn(Throwable failure, CapturedOutput output) {
    StringWriter stackTrace = new StringWriter();
    failure.printStackTrace(new PrintWriter(stackTrace));
    for (String key : new String[] {"test-alpha-key", "test-beta-key", "test-gamma-key"}) {
      assertThat(stackTrace.toString()).doesNotContain(key);
      assertThat(output.getAll()).doesNotContain(key);
    }
  }
}
