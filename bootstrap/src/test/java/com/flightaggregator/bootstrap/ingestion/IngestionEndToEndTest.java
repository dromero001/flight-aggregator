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

import com.flightaggregator.adapter.persistence.support.PostgresImage;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.bootstrap.config.ProviderBreakers;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
@Testcontainers
class IngestionEndToEndTest {

  /** Named for the reason given in {@code FlightAggregatorApplicationTest}. */
  @Container
  @ServiceConnection(name = "postgres")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(PostgresImage.NAME).asCompatibleSubstituteFor("postgres"));

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
  @Autowired private ApplicationContext context;
  @Autowired private LockProvider locks;

  /** The counters as each test found them: the context, and its meters, outlive a test. */
  private final Map<Meter.Id, Double> countsBefore = new HashMap<>();

  @BeforeEach
  void startFromAnEmptyInventoryWithEveryProviderUp() {
    jdbc.sql("TRUNCATE provider_offer, itinerary_a, itinerary_b").update();
    jdbc.sql(
            "UPDATE inventory_publication"
                + " SET active_slot = 'a', generation = 0, published_at = NULL")
        .update();
    letTheLockLapse();
    ProviderBreakers.closeAll(context);
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

  @Test
  void ingestsWithOneProviderDown() {
    job.run();
    Instant betaQuotedAt = storedQuotedAt(BETA);
    down(BETA_PAGE);
    letTheLockLapse();

    job.run();

    assertThat(generation()).isEqualTo(2);
    Map<String, Instant> published = published();
    assertThat(published.keySet()).containsExactlyInAnyOrder(EVERY_ITINERARY);
    assertThat(published.get("IB3170 BA117")).isEqualTo(betaQuotedAt);
    assertThat(downloads(ALPHA, "FRESH")).isEqualTo(2);
    assertThat(downloads(BETA, "KEPT")).isEqualTo(1);
    assertThat(downloads(GAMMA, "FRESH")).isEqualTo(2);
  }

  @Test
  void ingestsWithTwoProvidersDown() {
    job.run();
    down(BETA_PAGE);
    down(GAMMA_PAGE);
    letTheLockLapse();

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
    letTheLockLapse();

    job.run();
    letTheLockLapse();
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

  /**
   * Ends the lock that the last run keeps for a minute after it ({@code lockAtLeastFor}), as the
   * time between two scheduled runs does. ShedLock's own advice is to change the row, never to
   * delete it: it remembers the rows it has inserted.
   */
  private void letTheLockLapse() {
    jdbc.sql("UPDATE shedlock SET lock_until = locked_at").update();
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
