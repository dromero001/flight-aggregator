package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.persistence.support.ValkeyTestServer;
import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.SearchKey;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.json.JsonFactory;

class ValkeySearchCacheTest {

  private static final SearchCriteria FROM_MADRID =
      new SearchCriteria(
          Optional.of(new Airport("MAD")),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          SortOrder.PRICE);

  private static final Itinerary MADRID_NEW_YORK =
      new Itinerary(
          List.of(
              leg(
                  "IB",
                  3166,
                  "MAD",
                  "LHR",
                  "2026-11-20T08:15:00+01:00",
                  "2026-11-20T09:30Z",
                  "0.01"),
              leg(
                  "BA",
                  117,
                  "LHR",
                  "JFK",
                  "2026-11-20T11:00:00+00:00",
                  "2026-11-20T14:00:00-05:00",
                  "450.10")));

  private static final Itinerary MADRID_PARIS =
      new Itinerary(
          List.of(
              leg(
                  "UX",
                  1027,
                  "MAD",
                  "CDG",
                  "2026-11-21T06:00:00+01:00",
                  "2026-11-21T08:05:00+01:00",
                  "89.99")));

  private final StringRedisTemplate valkey = ValkeyTestServer.valkey();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ValkeySearchCache cache =
      new ValkeySearchCache(
          valkey,
          new JsonFactory(),
          Duration.ofMinutes(5),
          new ValkeyFailures(
              CircuitBreaker.of(
                  "valkey", ValkeyFailures.circuitBreakerConfig(Duration.ofSeconds(10)))),
          meters);

  private final Logger logger = (Logger) LoggerFactory.getLogger(ValkeySearchCache.class);
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void startFromAnEmptyValkeyAndCaptureTheLog() {
    ValkeyTestServer.empty();
    log.start();
    logger.addAppender(log);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(log);
  }

  /** Every field of the page comes back as it was put, each leg at its own offset. */
  @Test
  void roundTripsAPage() {
    SearchPage withMore =
        new SearchPage(
            List.of(MADRID_PARIS, MADRID_NEW_YORK),
            Optional.of(new PageCursor(4, SortOrder.PRICE, SeekPosition.of(MADRID_NEW_YORK))),
            true);
    SearchPage last = new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false);
    SearchKey firstKey = new SearchKey(4, FROM_MADRID, 2);
    SearchKey lastKey = new SearchKey(4, FROM_MADRID, 20);

    cache.put(firstKey, withMore);
    cache.put(lastKey, last);

    assertThat(cache.get(firstKey)).contains(withMore);
    assertThat(cache.get(lastKey)).contains(last);
  }

  /** The page is kept for the cache's time to live (§6.4: 5 min), then Valkey drops it. */
  @Test
  void expiresAfterItsTtl() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);

    cache.put(key, new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false));

    assertThat(valkey.getExpire(key.value(), TimeUnit.SECONDS)).isBetween(295L, 300L);
  }

  /** A new generation finds none of the pages of the one before it (§6.2). */
  @Test
  void keysDifferByGeneration() {
    SearchPage page = new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false);

    cache.put(new SearchKey(4, FROM_MADRID, 20), page);

    assertThat(cache.get(new SearchKey(5, FROM_MADRID, 20))).isEmpty();
    assertThat(cache.get(new SearchKey(4, FROM_MADRID, 20))).contains(page);
  }

  /** The hit ratio of §6.2, from {@code search.cache}, tagged with each lookup's result. */
  @Test
  void countsEachLookupAsAHitOrAMiss() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);

    cache.get(key);
    cache.put(key, new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false));
    cache.get(key);
    cache.get(key);

    assertThat(lookups("hit")).isEqualTo(2);
    assertThat(lookups("miss")).isEqualTo(1);
    assertThat(lookups("error")).isZero();
  }

  /**
   * A page this version cannot read, such as one an older version wrote, is not cached for it: the
   * lookup finds nothing and counts as an error, not a hit, instead of failing the search (§6.2).
   */
  @Test
  void findsNothingInAPageItCannotRead() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);
    valkey.opsForValue().set(key.value(), "{\"itineraries\":\"not a page\"}");

    assertThat(cache.get(key)).isEmpty();
    assertThat(lookups("error")).isEqualTo(1);
    assertThat(lookups("hit") + lookups("miss")).isZero();
  }

  /**
   * A page that cannot be read is one WARN that says what it is, and nothing of its content or of
   * the parser's failure, which quotes the content.
   */
  @Test
  void warnsOfAPageItCannotReadWithoutItsContent() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);
    valkey.opsForValue().set(key.value(), "not json");

    cache.get(key);

    assertThat(log.list)
        .extracting(
            ILoggingEvent::getLevel,
            ILoggingEvent::getFormattedMessage,
            ILoggingEvent::getThrowableProxy)
        .containsExactly(
            tuple(
                Level.WARN,
                "A cached search page cannot be read, so the search runs without it",
                null));
  }

  /** Valkey down, a lookup is a miss, counted as an error rather than a miss (§6.2). */
  @Test
  void findsNothingWhileValkeyIsDown() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);
    cache.put(key, new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false));

    assertThat(ValkeyTestServer.whileDown(() -> cache.get(key))).isEmpty();
    assertThat(lookups("error")).isEqualTo(1);
    assertThat(lookups("hit") + lookups("miss")).isZero();
  }

  /** Valkey down, a page is not kept, and the search that read it goes on (§6.2). */
  @Test
  void dropsAPageWhileValkeyIsDown() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);
    cache.get(key);

    ValkeyTestServer.whileDown(
        () -> cache.put(key, new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false)));
  }

  private double lookups(String result) {
    return meters.get("search.cache").tag("result", result).counter().count();
  }

  private static Leg leg(
      String carrier,
      int number,
      String from,
      String to,
      String departure,
      String arrival,
      String price) {
    return new Leg(
        new Carrier(carrier),
        number,
        new Airport(from),
        new Airport(to),
        OffsetDateTime.parse(departure),
        OffsetDateTime.parse(arrival),
        Money.euros(price),
        Provider.BETA,
        Instant.parse("2026-11-15T10:00:00.123456Z"));
  }
}
