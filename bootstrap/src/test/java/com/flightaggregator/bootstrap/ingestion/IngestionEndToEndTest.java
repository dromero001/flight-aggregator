package com.flightaggregator.bootstrap.ingestion;

import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.bootstrap.PostgresContainers;
import com.flightaggregator.bootstrap.observability.LoggedDiscardedRecords;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Whole runs of the job, against PostgreSQL and one WireMock server that answers for the three
 * providers (§6.5: ingestion with 1, 2 and 3 providers down). The network is small and fixed:
 * MAD-LHR on Alpha, LHR-JFK on Beta, MAD-JFK and a self-contradicting pair on Gamma, and one
 * malformed record per provider. One application context serves every test; each starts from an
 * empty inventory, the three providers answering, closed circuit breakers and the job's lock free.
 * The schedule is off: each test calls the job, which takes its lock, as the schedule would.
 */
@SpringBootTest(
    properties = {
      "providers.alpha.api-key=alpha-key",
      "providers.beta.api-key=beta-key",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers(PostgresContainers.class)
class IngestionEndToEndTest {

  /** Reset before each test by the extension itself. */
  @RegisterExtension
  static final WireMockExtension PROVIDERS =
      WireMockExtension.newInstance()
          .options(WireMockConfiguration.options().dynamicPort())
          .build();

  private static final String ALPHA_PAGE = "/alpha/legs?limit=1000";
  private static final String BETA_PAGE = "/beta/segments?page=1&pageSize=1000";
  private static final String GAMMA_PAGE = "/gamma/legs.csv?offset=0&limit=1000";

  /** Every itinerary of the network, by its flights. */
  private static final String[] EVERY_ITINERARY = {"IB3170", "BA117", "IB3170 BA117", "IB6251"};

  /** Three days ahead, so that every leg is still sellable whenever the tests run. */
  private static final LocalDate DAY = LocalDate.now(ZoneOffset.UTC).plusDays(3);

  @DynamicPropertySource
  static void providerUrls(DynamicPropertyRegistry registry) {
    for (Provider provider : Provider.values()) {
      registry.add(
          "providers." + provider.name().toLowerCase(Locale.ROOT) + ".base-url",
          PROVIDERS::baseUrl);
    }
  }

  @Autowired private IngestionJob job;
  @Autowired private JdbcClient jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private CircuitBreakerRegistry breakers;
  @Autowired private LockProvider locks;

  /** The counters as each test found them: the context, and its meters, outlive a test. */
  private final Map<Meter.Id, Double> countsBefore = new HashMap<>();

  @BeforeEach
  void startFromAnEmptyInventoryWithEveryProviderUp() {
    PostgresContainers.emptyTheInventory(jdbc);
    // The failures one test provokes must not open a breaker for the next.
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    for (Meter meter : meters.getMeters()) {
      if (meter instanceof Counter counter) {
        countsBefore.put(counter.getId(), counter.count());
      }
    }
    answer(ALPHA, ALPHA_PAGE, alphaPage());
    answer(BETA, BETA_PAGE, betaPage());
    answer(GAMMA, GAMMA_PAGE, gammaPage());
  }

  @Test
  void ingestsAllThreeProviders() {
    job.run();

    assertThat(generation()).isEqualTo(1);
    assertThat(published().keySet()).containsExactlyInAnyOrder(EVERY_ITINERARY);
    for (Provider provider : Provider.values()) {
      assertThat(downloads(provider, "FRESH")).as(provider.name()).isEqualTo(1);
    }
    assertThat(discarded(ALPHA, DiscardReason.PRICE)).isEqualTo(1);
    assertThat(discarded(BETA, DiscardReason.PRICE)).isEqualTo(1);
    assertThat(discarded(GAMMA, DiscardReason.SHAPE)).isEqualTo(1);
    assertThat(discarded(GAMMA, DiscardReason.CONFLICTING)).isEqualTo(2);
  }

  /**
   * The discarded records are logged on the job's own thread, after the downloads, so their lines
   * carry the run's id like the run's own line (§6.10).
   */
  @Test
  void tagsEachDiscardedRecordWithTheRunsId() {
    ListAppender<ILoggingEvent> log = new ListAppender<>();
    log.start();
    Logger jobLog = (Logger) LoggerFactory.getLogger(IngestionJob.class);
    Logger discardLog = (Logger) LoggerFactory.getLogger(LoggedDiscardedRecords.class);
    jobLog.addAppender(log);
    discardLog.addAppender(log);
    try {
      job.run();
    } finally {
      jobLog.detachAppender(log);
      discardLog.detachAppender(log);
    }

    String runId =
        log.list.stream()
            .filter(event -> event.getFormattedMessage().equals("Ingestion run ended"))
            .map(event -> event.getMDCPropertyMap().get("correlation.id"))
            .findFirst()
            .orElseThrow();
    assertThat(log.list)
        .filteredOn(event -> event.getLoggerName().equals(LoggedDiscardedRecords.class.getName()))
        .hasSize(5)
        .extracting(event -> event.getMDCPropertyMap().get("correlation.id"))
        .containsOnly(runId);
  }

  @Test
  void ingestsWithOneProviderDown() {
    job.run();
    Instant betaQuotedAt = storedQuotedAt(BETA);
    down(BETA_PAGE);

    job.run();

    assertThat(generation()).isEqualTo(2);
    Map<String, Instant> published = published();
    assertThat(published.keySet()).containsExactlyInAnyOrder(EVERY_ITINERARY);
    assertThat(published.get("IB3170 BA117")).isEqualTo(betaQuotedAt);
    assertThat(downloads(ALPHA, "FRESH")).isEqualTo(2);
    assertThat(downloads(BETA, "KEPT")).isEqualTo(1);
    assertThat(downloads(GAMMA, "FRESH")).isEqualTo(2);
  }

  /**
   * The production wiring keeps day partitions from the run's day to 37 days ahead (§4.3): the
   * 30-day horizon plus 7 days, should runs fail. Read from the published slot, by its own day.
   */
  @Test
  void partitionsThirtySevenDaysAheadOfTheRunsDay() {
    // Earlier tests' partitions stay in the slots; without them, the last one is this run's.
    for (String slot : List.of("itinerary_a", "itinerary_b")) {
      jdbc.sql(
              "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                  + " WHERE i.inhparent = CAST(? AS regclass) AND c.relname <> ?")
          .param(slot)
          .param(slot + "_default")
          .query(String.class)
          .list()
          .forEach(partition -> jdbc.sql("DROP TABLE " + partition).update());
    }

    job.run();

    String slot =
        jdbc.sql("SELECT active_slot FROM inventory_publication").query(String.class).single();
    LocalDate runDay =
        jdbc.sql("SELECT published_at FROM inventory_publication")
            .query(OffsetDateTime.class)
            .single()
            .atZoneSameInstant(ZoneOffset.UTC)
            .toLocalDate();
    String lastDayPartition =
        jdbc.sql(
                "SELECT max(c.relname) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                    + " WHERE i.inhparent = CAST(? AS regclass) AND c.relname <> ?")
            .param("itinerary_" + slot)
            .param("itinerary_" + slot + "_default")
            .query(String.class)
            .single();

    assertThat(lastDayPartition)
        .isEqualTo(
            "itinerary_"
                + slot
                + "_"
                + runDay.plusDays(37).format(DateTimeFormatter.BASIC_ISO_DATE));
  }

  /** Beta's page fails its three attempts: two retries, counted under Beta alone (§6.10). */
  @Test
  void countsEachRetryUnderItsProvider() {
    down(BETA_PAGE);

    job.run();

    assertThat(counted("ingestion.provider.retries", "provider", "BETA")).isEqualTo(2);
    assertThat(counted("ingestion.provider.retries", "provider", "ALPHA")).isZero();
    assertThat(counted("ingestion.provider.retries", "provider", "GAMMA")).isZero();
  }

  @Test
  void ingestsWithTwoProvidersDown() {
    job.run();
    down(BETA_PAGE);
    down(GAMMA_PAGE);

    job.run();

    assertThat(generation()).isEqualTo(2);
    assertThat(published().keySet()).containsExactlyInAnyOrder(EVERY_ITINERARY);
    assertThat(downloads(ALPHA, "FRESH")).isEqualTo(2);
    assertThat(downloads(BETA, "KEPT")).isEqualTo(1);
    assertThat(downloads(GAMMA, "KEPT")).isEqualTo(1);
  }

  /**
   * Two runs in a row with every provider down still publish the first run's itineraries, quotes
   * included.
   */
  @Test
  void keepsTheLastGoodInventoryWithAllThreeDown() {
    job.run();
    Map<String, Instant> lastGood = published();
    down(ALPHA_PAGE);
    down(BETA_PAGE);
    down(GAMMA_PAGE);

    job.run();
    job.run();

    assertThat(generation()).isEqualTo(3);
    assertThat(published()).isEqualTo(lastGood);
    assertThat(lastGood.keySet()).containsExactlyInAnyOrder(EVERY_ITINERARY);
    for (Provider provider : Provider.values()) {
      assertThat(downloads(provider, "KEPT")).as(provider.name()).isEqualTo(2);
    }
  }

  @Test
  void publishesNothingWhenAllThreeAreDownOnTheFirstRun() {
    down(ALPHA_PAGE);
    down(BETA_PAGE);
    down(GAMMA_PAGE);

    job.run();

    assertThat(generation()).isZero();
    assertThat(published()).isEmpty();
    assertThat(counted("ingestion.runs", "outcome", "NOTHING_TO_PUBLISH")).isEqualTo(1);
    for (Provider provider : Provider.values()) {
      assertThat(downloads(provider, "KEPT")).as(provider.name()).isEqualTo(1);
    }
  }

  /** Another instance holds the ingestion's lock: this one skips its run instead of waiting. */
  @Test
  void aSecondRunWhileOneHoldsTheLockIsSkipped() {
    SimpleLock held =
        locks
            .lock(
                new LockConfiguration(
                    Instant.now(), "ingestion", Duration.ofMinutes(5), Duration.ZERO))
            .orElseThrow();
    try {
      job.run();
    } finally {
      held.unlock();
    }

    assertThat(generation()).isZero();
    PROVIDERS.verify(0, anyRequestedFor(anyUrl()));
  }

  private long generation() {
    return jdbc.sql("SELECT generation FROM inventory_publication").query(Long.class).single();
  }

  /**
   * Each itinerary search can read, by its flights in order (for example {@code IB3170 BA117}),
   * with its quote: the oldest of its legs'.
   */
  private Map<String, Instant> published() {
    String slot =
        jdbc.sql("SELECT active_slot FROM inventory_publication").query(String.class).single();
    Map<String, Instant> published = new HashMap<>();
    jdbc.sql(
            "SELECT (SELECT string_agg((leg ->> 'carrier') || (leg ->> 'flightNumber'), ' '"
                + " ORDER BY n) FROM jsonb_array_elements(legs) WITH ORDINALITY AS l(leg, n)),"
                + " quoted_at FROM itinerary_"
                + slot)
        .query(
            row -> {
              published.put(row.getString(1), row.getObject(2, OffsetDateTime.class).toInstant());
            });
    return published;
  }

  /** The quote of the provider's one stored offer. */
  private Instant storedQuotedAt(Provider provider) {
    return jdbc.sql("SELECT quoted_at FROM provider_offer WHERE provider = ?")
        .param(provider.name())
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private double downloads(Provider provider, String status) {
    return counted("ingestion.provider.downloads", "provider", provider.name(), "status", status);
  }

  private double discarded(Provider provider, DiscardReason reason) {
    return counted(
        "ingestion.records.discarded", "provider", provider.name(), "reason", reason.name());
  }

  /** How much the counter rose during this test. */
  private double counted(String name, String... tags) {
    Counter counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count() - countsBefore.getOrDefault(counter.getId(), 0.0);
  }

  /** The provider answers its one page with the body, to its own key only. */
  private static void answer(Provider provider, String page, String body) {
    PROVIDERS.stubFor(
        get(urlEqualTo(page))
            .withHeader("X-Api-Key", equalTo(provider.name().toLowerCase(Locale.ROOT) + "-key"))
            .willReturn(ok(body)));
  }

  /** The provider answers its page with a server error. */
  private static void down(String page) {
    PROVIDERS.stubFor(get(urlEqualTo(page)).willReturn(serverError()));
  }

  /** IB3170 MAD-LHR, and IB3172 with a negative price. */
  private static String alphaPage() {
    return "{\"legs\":["
        + alphaLeg(3170, "08:15:00+01:00", "09:30:00+00:00", "129.90")
        + ","
        + alphaLeg(3172, "18:15:00+01:00", "19:30:00+00:00", "-1.00")
        + "],\"nextCursor\":null}";
  }

  private static String alphaLeg(int flight, String departure, String arrival, String price) {
    return "{\"carrier\":\"IB\",\"flightNumber\":"
        + flight
        + ",\"origin\":\"MAD\",\"destination\":\"LHR\",\"departureTime\":\""
        + DAY
        + "T"
        + departure
        + "\",\"arrivalTime\":\""
        + DAY
        + "T"
        + arrival
        + "\",\"price\":\""
        + price
        + "\"}";
  }

  /** BA117 LHR-JFK, leaving 90 minutes after IB3170 lands, and BA119 with a price that is text. */
  private static String betaPage() {
    return "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments>"
        + betaSegment(117, "11:00:00+00:00", "14:00:00-05:00", "45000")
        + betaSegment(119, "15:00:00+00:00", "18:00:00-05:00", "abc")
        + "</Segments></SegmentsResponse>";
  }

  private static String betaSegment(int flight, String departure, String arrival, String cents) {
    return "<Segment><Carrier code=\"BA\"/><FlightNumber>"
        + flight
        + "</FlightNumber><Departure airport=\"LHR\">"
        + DAY
        + "T"
        + departure
        + "</Departure><Arrival airport=\"JFK\">"
        + DAY
        + "T"
        + arrival
        + "</Arrival><PriceCents>"
        + cents
        + "</PriceCents></Segment>";
  }

  /**
   * IB6251 MAD-JFK; UX1015 MAD-LHR twice with two arrivals, which contradicts itself; and a line
   * with three cells.
   */
  private static String gammaPage() {
    return "airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n"
        + gammaLine("IB,6251,MAD,JFK", "12:00:00+01:00", "14:30:00-05:00", "520.00")
        + gammaLine("UX,1015,MAD,LHR", "15:00:00+01:00", "16:15:00+00:00", "99.00")
        + gammaLine("UX,1015,MAD,LHR", "15:00:00+01:00", "16:45:00+00:00", "99.00")
        + "IB,6253,MAD\r\n";
  }

  private static String gammaLine(String flight, String departure, String arrival, String price) {
    return flight + "," + DAY + "T" + departure + "," + DAY + "T" + arrival + "," + price + "\r\n";
  }
}
