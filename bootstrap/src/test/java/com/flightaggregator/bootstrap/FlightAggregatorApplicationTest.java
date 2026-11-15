package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.adapter.persistence.support.PostgresImage;
import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      FlightAggregatorApplicationTest.ALPHA_URL,
      FlightAggregatorApplicationTest.ALPHA_KEY,
      FlightAggregatorApplicationTest.BETA_URL,
      FlightAggregatorApplicationTest.BETA_KEY,
      FlightAggregatorApplicationTest.GAMMA_URL,
      FlightAggregatorApplicationTest.GAMMA_KEY,
      "ingestion.schedule.enabled=false"
    })
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class FlightAggregatorApplicationTest {

  // Nothing listens on port 1, so a provider call fails at once.
  static final String ALPHA_URL = "providers.alpha.base-url=http://localhost:1/";
  static final String ALPHA_KEY = "providers.alpha.api-key=test-alpha-key";
  static final String BETA_URL = "providers.beta.base-url=http://localhost:1/";
  static final String BETA_KEY = "providers.beta.api-key=test-beta-key";
  static final String GAMMA_URL = "providers.gamma.base-url=http://localhost:1/";
  static final String GAMMA_KEY = "providers.gamma.api-key=test-gamma-key";

  /**
   * Named, because Boot otherwise deduces the name from the image, and Testcontainers' check of an
   * image name refuses one pinned by both tag and digest.
   */
  @Container
  @ServiceConnection(name = "postgres")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(PostgresImage.NAME).asCompatibleSubstituteFor("postgres"));

  @Autowired private HealthEndpoint health;
  @Autowired private Flyway flyway;
  @Autowired private OfferStore offers;
  @Autowired private ItineraryRepository itineraries;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcClient jdbc;
  @Autowired private IngestInventory ingestion;
  @Autowired private DiscardedRecords discarded;
  @Autowired private JsonMapper json;

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor tasks;

  @Test
  void startsAgainstPostgresAndMigrates() {
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    assertThat(flyway.info().applied())
        .extracting(migration -> migration.getVersion().getVersion(), MigrationInfo::getState)
        .containsExactly(
            tuple("1", MigrationState.SUCCESS),
            tuple("2", MigrationState.SUCCESS),
            tuple("3", MigrationState.SUCCESS));
  }

  /**
   * The stores write in the {@code TransactionTemplate}'s transaction, which holds only when the
   * template and every store use Boot's one {@code DataSource}. A store given another one would
   * write on connections of its own that commit each statement, so a rollback would leave its
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
          assertThat(offers.offers(Provider.ALPHA)).extracting(Leg::key).containsExactly(leg.key());
          assertThat(publication.generation()).isEqualTo(generation + 1);
          status.setRollbackOnly();
        });

    assertThat(offers.offers(Provider.ALPHA)).isEmpty();
    assertThat(generation()).isEqualTo(generation);
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

  /** Boot's own tasks, and the schedule of Task 10 with them, run on virtual threads (§6.1). */
  @Test
  void runsBootsTasksOnVirtualThreads() throws Exception {
    assertThat(tasks.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
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
                    "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                    "spring.datasource.username=" + POSTGRES.getUsername(),
                    "spring.datasource.password=" + POSTGRES.getPassword(),
                    "ingestion.schedule.enabled=false"),
                Stream.of(ALPHA_URL, ALPHA_KEY, BETA_URL, BETA_KEY, GAMMA_URL, GAMMA_KEY)
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
