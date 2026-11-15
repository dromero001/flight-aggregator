package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.persistence.support.ValkeyTestServer;
import com.flightaggregator.domain.Airport;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class ValkeyRefreshCoordinationTest {

  private static final Airport MADRID = new Airport("MAD");
  private static final Airport BARCELONA = new Airport("BCN");

  /** Short, so that a test can wait for a claim to expire. */
  private static final Duration CLAIM_TTL = Duration.ofMillis(300);

  private final StringRedisTemplate valkey = ValkeyTestServer.valkey();
  private final ValkeyRefreshCoordination coordination =
      new ValkeyRefreshCoordination(
          valkey,
          CLAIM_TTL,
          Duration.ofMinutes(10),
          new ValkeyFailures(
              CircuitBreaker.of(
                  "valkey", ValkeyFailures.circuitBreakerConfig(Duration.ofSeconds(10)))));

  private final Logger logger = (Logger) LoggerFactory.getLogger(ValkeyRefreshCoordination.class);
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

  /** One caller per origin gets the claim, under {@code refresh:<origin>}, until it expires. */
  @Test
  void claimsOncePerOriginUntilTheClaimExpires() throws InterruptedException {
    assertThat(coordination.claim(MADRID)).isTrue();
    assertThat(coordination.claim(MADRID)).isFalse();
    assertThat(coordination.claim(BARCELONA)).isTrue();
    assertThat(valkey.getExpire("refresh:MAD", TimeUnit.MILLISECONDS)).isBetween(1L, 300L);

    Thread.sleep(CLAIM_TTL.plusMillis(200));

    assertThat(coordination.claim(MADRID)).isTrue();
  }

  /**
   * The time is kept under {@code fresh:<origin>} for the freshness threshold (§6.4: 10 min), after
   * which the origin is stale whatever the time said.
   */
  @Test
  void remembersWhenAnOriginWasRefreshed() {
    Instant refreshedAt = Instant.parse("2026-11-20T10:00:00.123456Z");
    assertThat(coordination.refreshedAt(MADRID)).isEmpty();

    coordination.refreshed(MADRID, refreshedAt);

    assertThat(coordination.refreshedAt(MADRID)).contains(refreshedAt);
    assertThat(coordination.refreshedAt(BARCELONA)).isEmpty();
    assertThat(valkey.getExpire("fresh:MAD", TimeUnit.SECONDS)).isBetween(595L, 600L);
  }

  /**
   * A mark this version cannot read, such as one another version wrote, is no refresh: the origin
   * looks stale and is refreshed again, instead of the search failing (§6.2).
   */
  @Test
  void knowsOfNoRefreshFromAMarkItCannotRead() {
    valkey.opsForValue().set("fresh:MAD", "yesterday");

    assertThat(coordination.refreshedAt(MADRID)).isEmpty();
  }

  /**
   * A mark that cannot be read is one WARN that says what it is, and nothing of its content or of
   * the parser's failure, which quotes the content.
   */
  @Test
  void warnsOfAMarkItCannotReadWithoutItsContent() {
    valkey.opsForValue().set("fresh:MAD", "yesterday");

    coordination.refreshedAt(MADRID);

    assertThat(log.list)
        .extracting(
            ILoggingEvent::getLevel,
            ILoggingEvent::getFormattedMessage,
            ILoggingEvent::getThrowableProxy)
        .containsExactly(
            tuple(
                Level.WARN,
                "An origin's refresh mark cannot be read, so the origin counts as not refreshed",
                null));
  }

  /** Valkey down, no caller gets a claim, so no refresh starts until it is back (§6.2). */
  @Test
  void claimsNothingWhileValkeyIsDown() {
    coordination.refreshedAt(MADRID);

    assertThat(ValkeyTestServer.whileDown(() -> coordination.claim(MADRID))).isFalse();
  }

  /** Valkey down, no refresh is known: the origin looks stale, and the claim then fails too. */
  @Test
  void knowsOfNoRefreshWhileValkeyIsDown() {
    coordination.refreshed(MADRID, Instant.parse("2026-11-20T10:00:00Z"));

    assertThat(ValkeyTestServer.whileDown(() -> coordination.refreshedAt(MADRID))).isEmpty();
  }

  /** Valkey down, a finished refresh is not recorded, and the refresh still ends normally. */
  @Test
  void dropsARefreshWhileValkeyIsDown() {
    coordination.refreshedAt(MADRID);

    ValkeyTestServer.whileDown(
        () -> coordination.refreshed(MADRID, Instant.parse("2026-11-20T10:00:00Z")));
  }
}
