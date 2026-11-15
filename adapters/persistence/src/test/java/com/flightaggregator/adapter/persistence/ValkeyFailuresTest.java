package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.ValkeyTestServer.whileDown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.persistence.support.ValkeyTestServer;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.SearchKey;
import com.flightaggregator.application.port.out.WindowCount;
import com.flightaggregator.domain.Airport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

  private static final SearchPage NOTHING_FOUND =
      new SearchPage(List.of(), Optional.empty(), false);

  private final StringRedisTemplate valkey = ValkeyTestServer.valkey();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ValkeyFailures failures = new ValkeyFailures();
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
   * The first command Valkey answers after an outage is one INFO line, whichever store sends it,
   * and the next outage is a WARN again: each outage shows its start and its end.
   */
  @Test
  void logsTheEndOfAnOutageAndTheNextOne() {
    cache.get(KEY);
    whileDown(() -> cache.get(KEY));
    whileDown(() -> counter.increment("search:203.0.113.7", Duration.ofMinutes(1)));

    coordination.refreshedAt(MADRID);
    cache.get(KEY);
    whileDown(() -> coordination.claim(MADRID));

    assertThat(log.list)
        .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
        .containsExactly(
            tuple(Level.WARN, UNAVAILABLE),
            tuple(Level.INFO, "Valkey answers again"),
            tuple(Level.WARN, UNAVAILABLE));
  }

  private static String cause(ILoggingEvent event) {
    return event.getThrowableProxy() == null ? null : event.getThrowableProxy().getClassName();
  }
}
