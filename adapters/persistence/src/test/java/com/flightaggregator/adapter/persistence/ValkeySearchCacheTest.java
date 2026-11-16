package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.persistence.support.SettableClock;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
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

  /** The cache's time to live (§6.4). */
  private static final Duration TTL = Duration.ofMinutes(5);

  /** When each test starts: before every flight of the fixtures above departs. */
  private static final Instant START = Instant.parse("2026-11-19T10:00:00Z");

  private final StringRedisTemplate valkey = ValkeyTestServer.valkey();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SettableClock clock = new SettableClock(START);
  private final ValkeySearchCache cache =
      new ValkeySearchCache(
          valkey,
          new JsonFactory(),
          TTL,
          new ValkeyFailures(
              CircuitBreaker.of(
                  "valkey", ValkeyFailures.circuitBreakerConfig(Duration.ofSeconds(10)))),
          meters,
          clock::instant);

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

  /**
   * The user's decision (Task 6, fix round 3): a page is kept no longer than until its first flight
   * leaves, so a cached page never holds a departed flight and a hit is always a page search can
   * serve. Its earliest departure, 90 s away, is its second row's.
   */
  @Test
  void keepsAPageNoLongerThanUntilItsFirstDeparture() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);

    cache.put(
        key,
        new SearchPage(
            List.of(departingAfter(Duration.ofMinutes(3)), departingAfter(Duration.ofSeconds(90))),
            Optional.empty(),
            false));

    assertThat(valkey.getExpire(key.value(), TimeUnit.MILLISECONDS)).isBetween(88_000L, 90_000L);
  }

  /**
   * A page whose first flight leaves after the cache's time to live is kept that long, no longer.
   */
  @Test
  void keepsTheCacheTtlWhenTheFirstDepartureIsLater() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);

    cache.put(
        key, new SearchPage(List.of(departingAfter(TTL.plusMinutes(1))), Optional.empty(), false));

    assertThat(valkey.getExpire(key.value(), TimeUnit.MILLISECONDS)).isBetween(298_000L, 300_000L);
  }

  /**
   * A page whose first flight has left, leaves now, or leaves sooner than Valkey's finest expiry (a
   * millisecond) is not kept at all: no time to live would keep it without a departed flight. Its
   * write is not sent, so Valkey cannot refuse it, and the cache goes on keeping other pages.
   */
  @ParameterizedTest(name = "first flight leaving {0} after the write")
  @ValueSource(strings = {"-PT1M", "PT0S", "PT0.0005S"})
  void keepsNoPageWhoseFirstFlightHasLeft(Duration wait) {
    SearchKey departed = new SearchKey(4, FROM_MADRID, 20);
    SearchKey later = new SearchKey(4, FROM_MADRID, 50);
    SearchPage laterPage = new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false);

    cache.put(departed, new SearchPage(List.of(departingAfter(wait)), Optional.empty(), false));
    cache.put(later, laterPage);

    assertThat(valkey.hasKey(departed.value())).isFalse();
    assertThat(cache.get(later)).contains(laterPage);
  }

  /** A page with no flights, as for a route nobody flies, has no departure to wait for. */
  @Test
  void keepsAnEmptyPageForTheCacheTtl() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);

    cache.put(key, new SearchPage(List.of(), Optional.empty(), false));

    assertThat(valkey.getExpire(key.value(), TimeUnit.MILLISECONDS)).isBetween(298_000L, 300_000L);
  }

  /**
   * A time to live under a millisecond is Valkey's zero, an expiry it refuses: the page is not
   * sent, so the refusal is not taken for an outage that would skip Valkey for every store sharing
   * its failures.
   */
  @Test
  void sendsNoPageWhoseTimeToLiveIsUnderAMillisecond() {
    ValkeyFailures shared =
        new ValkeyFailures(
            CircuitBreaker.of(
                "valkey", ValkeyFailures.circuitBreakerConfig(Duration.ofSeconds(10))));
    ValkeySearchCache underAMillisecond =
        new ValkeySearchCache(
            valkey, new JsonFactory(), Duration.ofNanos(500_000), shared, meters, clock::instant);
    ValkeySearchCache sharingItsFailures =
        new ValkeySearchCache(valkey, new JsonFactory(), TTL, shared, meters, clock::instant);
    SearchKey dropped = new SearchKey(4, FROM_MADRID, 20);
    SearchKey kept = new SearchKey(5, FROM_MADRID, 20);

    underAMillisecond.put(dropped, new SearchPage(List.of(), Optional.empty(), false));
    sharingItsFailures.put(kept, new SearchPage(List.of(), Optional.empty(), false));

    assertThat(valkey.hasKey(dropped.value())).isFalse();
    assertThat(valkey.hasKey(kept.value())).isTrue();
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

  /**
   * Many pages that cannot be read at once, as after a change of their format, are one WARN, not
   * one each; the meter still counts every one.
   */
  @Test
  void warnsOnceOfManyPagesItCannotReadAtOnce() {
    List<SearchKey> keys =
        List.of(
            new SearchKey(4, FROM_MADRID, 20),
            new SearchKey(4, FROM_MADRID, 40),
            new SearchKey(5, FROM_MADRID, 20));
    keys.forEach(key -> valkey.opsForValue().set(key.value(), "not json"));

    keys.forEach(cache::get);

    assertThat(log.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    assertThat(lookups("error")).isEqualTo(3);
  }

  /**
   * That WARN's episode lasts the cache's time to live, the longest a page written in another
   * format can still be found. The first page that cannot be read after it is a WARN again, which
   * counts the ones that were not logged and says when the episode they belong to began, so the
   * count is not read as the new WARN's own.
   */
  @Test
  void warnsAgainOnceItsTimeToLiveHasPassedCountingTheOnesNotLogged() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);
    valkey.opsForValue().set(key.value(), "not json");

    cache.get(key);
    cache.get(key);
    cache.get(key);
    clock.advance(TTL.minusMillis(1));
    cache.get(key);
    clock.advance(Duration.ofMillis(1));
    cache.get(key);

    assertThat(log.list)
        .extracting(ILoggingEvent::getLevel, ILoggingEvent::getKeyValuePairs)
        .containsExactly(
            tuple(Level.WARN, List.of(new KeyValuePair("unloggedRepeats", 0L))),
            tuple(
                Level.WARN,
                List.of(
                    new KeyValuePair("unloggedRepeats", 3L),
                    new KeyValuePair("unloggedSince", START))));
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

  /**
   * Valkey down, a page is not kept, and the search that read it goes on (§6.2): the write neither
   * throws nor holds the search past the outage bound, which {@code whileDown} asserts.
   */
  @Test
  void dropsAPageWhileValkeyIsDown() {
    SearchKey key = new SearchKey(4, FROM_MADRID, 20);
    cache.get(key);

    assertThatNoException()
        .isThrownBy(
            () ->
                ValkeyTestServer.whileDown(
                    () ->
                        cache.put(
                            key, new SearchPage(List.of(MADRID_PARIS), Optional.empty(), false))));
  }

  private double lookups(String result) {
    return meters.get("search.cache").tag("result", result).counter().count();
  }

  /** A direct flight from Madrid to Paris leaving that long after the test starts. */
  private static Itinerary departingAfter(Duration wait) {
    OffsetDateTime departure = START.plus(wait).atOffset(ZoneOffset.ofHours(1));
    return new Itinerary(
        List.of(
            leg(
                "UX",
                1000 + (int) wait.toSeconds(),
                "MAD",
                "CDG",
                departure.toString(),
                departure.plusHours(2).toString(),
                "89.99")));
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
