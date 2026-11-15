package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.ValkeyTestServer;
import com.flightaggregator.domain.Airport;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class ValkeyRefreshCoordinationTest {

  private static final Airport MADRID = new Airport("MAD");
  private static final Airport BARCELONA = new Airport("BCN");

  /** Short, so that a test can wait for a claim to expire. */
  private static final Duration CLAIM_TTL = Duration.ofMillis(300);

  private final StringRedisTemplate valkey = ValkeyTestServer.valkey();
  private final ValkeyRefreshCoordination coordination =
      new ValkeyRefreshCoordination(
          valkey, CLAIM_TTL, Duration.ofMinutes(10), new ValkeyFailures());

  @BeforeEach
  void startFromAnEmptyValkey() {
    ValkeyTestServer.empty();
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
