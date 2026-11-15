package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.ValkeyTestServer.whileDown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.persistence.support.SettableClock;
import com.flightaggregator.adapter.persistence.support.ValkeyTestServer;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.SearchKey;
import com.flightaggregator.application.port.out.WindowCount;
import com.flightaggregator.domain.Airport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * The three Valkey stores sharing one {@link ValkeyFailures}, as the application wires them, with
 * Valkey frozen for each call. The log is read as Logback events on the class's logger.
 */
class ValkeyFailuresTest {

  private static final Airport MADRID = new Airport("MAD");

  private static final SearchKey KEY =
      new SearchKey(
          4,
          new SearchCriteria(
              Optional.of(MADRID),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              SortOrder.PRICE),
          20);

  private static final String UNAVAILABLE =
      "Valkey is unavailable: searches run without the result cache, refresh claims and request"
          + " counts until it answers again";

  /** How long Valkey is skipped after a failure (plan 5, the user's decision of 2026-11-15). */
  private static final Duration SKIP = Duration.ofSeconds(10);

  /** The commands {@link #search()} sends: the count's script, the two reads and the two writes. */
  private static final String[] SEARCH_COMMANDS = {"evalsha", "eval", "get", "set"};

  private static final SearchPage NOTHING_FOUND =
      new SearchPage(List.of(), Optional.empty(), false);

  private final StringRedisTemplate valkey = ValkeyTestServer.valkey();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** The breaker's clock, moved by the tests instead of waiting for the skip. */
  private final SettableClock clock = new SettableClock(Instant.parse("2026-11-20T10:00:00Z"));

  private final CircuitBreaker breaker =
      CircuitBreaker.of(
          "valkey",
          CircuitBreakerConfig.from(ValkeyFailures.circuitBreakerConfig(SKIP))
              .clock(clock)
              .build());
  private final ValkeyFailures failures = new ValkeyFailures(breaker);
  private final ValkeySearchCache cache =
      new ValkeySearchCache(valkey, new JsonFactory(), Duration.ofMinutes(5), failures, meters);
  private final ValkeyRefreshCoordination coordination =
      new ValkeyRefreshCoordination(
          valkey, Duration.ofMinutes(2), Duration.ofMinutes(10), failures);
  private final ValkeyRequestCounter counter = new ValkeyRequestCounter(valkey, failures);

  private final Logger logger = (Logger) LoggerFactory.getLogger(ValkeyFailures.class);
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

  /**
   * Review Focus 3: each operation gives its fail-open value within 500 ms, each failed lookup is
   * counted as an error, and the outage is one WARN line, not one per call.
   */
  @Test
  void failsOpenWhenValkeyIsDown() {
    cache.get(KEY);

    assertThat(whileDown(() -> cache.get(KEY))).isEmpty();
    whileDown(() -> cache.put(KEY, NOTHING_FOUND));
    assertThat(whileDown(() -> coordination.refreshedAt(MADRID))).isEmpty();
    assertThat(whileDown(() -> coordination.claim(MADRID))).isFalse();
    whileDown(() -> coordination.refreshed(MADRID, Instant.parse("2026-11-20T10:00:00Z")));
    assertThat(whileDown(() -> counter.increment("search:203.0.113.7", Duration.ofMinutes(1))))
        .isEqualTo(WindowCount.unavailable());
    assertThat(whileDown(() -> cache.get(KEY))).isEmpty();

    assertThat(meters.get("search.cache").tag("result", "error").counter().count()).isEqualTo(2);
    assertThat(log.list)
        .extracting(
            ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage, ValkeyFailuresTest::cause)
        .containsExactly(
            tuple(Level.WARN, UNAVAILABLE, "org.springframework.dao.QueryTimeoutException"));
  }

  /**
   * After a failure Valkey is skipped, even once it is back, until the skip has passed; then one
   * call probes it, and when Valkey answers every operation reaches it again. The outage's end is
   * one INFO line, and the next outage is a WARN again.
   */
  @Test
  void probesValkeyOnceTheSkipHasPassed() {
    cache.put(KEY, NOTHING_FOUND);
    whileDown(() -> cache.get(KEY));

    assertThat(cache.get(KEY)).isEmpty();
    clock.advance(SKIP.minusMillis(1));
    assertThat(cache.get(KEY)).isEmpty();
    clock.advance(Duration.ofMillis(2));

    assertThat(cache.get(KEY)).contains(NOTHING_FOUND);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(coordination.claim(MADRID)).isTrue();
    whileDown(() -> counter.increment("search:203.0.113.7", Duration.ofMinutes(1)));
    assertThat(log.list)
        .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
        .containsExactly(
            tuple(Level.WARN, UNAVAILABLE),
            tuple(Level.INFO, "Valkey answers again"),
            tuple(Level.WARN, UNAVAILABLE));
  }

  /**
   * Review Focus 3 per search: with Valkey hanging on an open connection, a burst of searches pays
   * one command timeout in all, not one per command, and after it each operation gets its fail-open
   * value at once, without being sent.
   */
  @Test
  void aBurstOfSearchesPaysOneTimeoutWhileValkeyHangs() {
    cache.get(KEY);

    whileDown(() -> concurrently(8, this::search));
    long sentBefore = ValkeyTestServer.commandsRun(SEARCH_COMMANDS);
    Duration next = whileDown(() -> elapsed(this::search));

    assertThat(next).isLessThan(Duration.ofMillis(50));
    assertThat(ValkeyTestServer.commandsRun(SEARCH_COMMANDS)).isEqualTo(sentBefore);
  }

  /**
   * Once the skip has passed, one call at a time probes Valkey: while it is in flight the others
   * get their fail-open value at once, so callers arriving while Valkey still hangs do not each pay
   * a timeout.
   */
  @Test
  void letsOneCallAtATimeProbeValkey() {
    cache.get(KEY);
    whileDown(() -> cache.get(KEY));
    clock.advance(SKIP.plusMillis(1));
    long getsBefore = ValkeyTestServer.commandsRun("get");

    whileDown(() -> concurrently(8, () -> cache.get(KEY)));

    assertThat(ValkeyTestServer.commandsRun("get") - getsBefore).isEqualTo(1);
  }

  /**
   * An outage is one WARN however long it lasts: each probe Valkey does not answer keeps it
   * skipped, and logs nothing more.
   */
  @Test
  void warnsOnceHoweverManyProbesFail() {
    cache.get(KEY);
    whileDown(() -> cache.get(KEY));
    long getsBefore = ValkeyTestServer.commandsRun("get");

    clock.advance(SKIP.plusMillis(1));
    whileDown(() -> cache.get(KEY));
    clock.advance(SKIP.plusMillis(1));
    whileDown(() -> cache.get(KEY));

    assertThat(ValkeyTestServer.commandsRun("get") - getsBefore).isEqualTo(2);
    assertThat(log.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
  }

  /**
   * C1: Valkey hangs before the client has ever connected. Until it has answered once, one caller
   * at a time tries it, so callers arriving together do not queue behind each other's connection
   * attempts: together they return within the bound. The outage is one WARN, as a later one is.
   */
  @Test
  void callersDoNotQueueBeforeTheFirstConnection() {
    ValkeyTestServer.withNewClient(
        neverConnected -> {
          ValkeySearchCache cacheBeforeItsFirstConnection =
              new ValkeySearchCache(
                  neverConnected,
                  new JsonFactory(),
                  Duration.ofMinutes(5),
                  new ValkeyFailures(
                      CircuitBreaker.of("valkey", ValkeyFailures.circuitBreakerConfig(SKIP))),
                  meters);

          whileDown(() -> concurrently(8, () -> cacheBeforeItsFirstConnection.get(KEY)));
        });

    assertThat(log.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
  }

  /**
   * A command that fails for a reason other than Valkey's is a bug: it is thrown, and Valkey is not
   * skipped because of it, even when it was the call that tried Valkey alone.
   */
  @Test
  void throwsABugWithoutSkippingValkey() {
    assertThatThrownBy(
            () ->
                failures.call(
                    () -> {
                      throw new IllegalStateException("a bug");
                    },
                    () -> null))
        .isInstanceOf(IllegalStateException.class);

    cache.put(KEY, NOTHING_FOUND);

    assertThat(cache.get(KEY)).contains(NOTHING_FOUND);
  }

  /**
   * What one search sends Valkey (Tasks 6 and 10): its request count, the cache lookup, the
   * origin's refresh mark and claim, and the page it read.
   */
  private void search() {
    counter.increment("search:203.0.113.7", Duration.ofMinutes(1));
    cache.get(KEY);
    coordination.refreshedAt(MADRID);
    coordination.claim(MADRID);
    cache.put(KEY, NOTHING_FOUND);
  }

  /** Runs the call on this many threads at once and returns when all have returned. */
  private static void concurrently(int callers, Runnable call) {
    CompletableFuture.allOf(
            IntStream.range(0, callers)
                .mapToObj(
                    caller ->
                        CompletableFuture.runAsync(call, task -> Thread.ofVirtual().start(task)))
                .toArray(CompletableFuture[]::new))
        .join();
  }

  private static Duration elapsed(Runnable call) {
    long started = System.nanoTime();
    call.run();
    return Duration.ofNanos(System.nanoTime() - started);
  }

  private static String cause(ILoggingEvent event) {
    return event.getThrowableProxy() == null ? null : event.getThrowableProxy().getClassName();
  }
}
