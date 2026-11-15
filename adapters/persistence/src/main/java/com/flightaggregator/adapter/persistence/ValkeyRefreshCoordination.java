package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.domain.Airport;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Refresh coordination in Valkey (§6.2): a claim is {@code SET refresh:<origin> NX} with the
 * claim's time to live, so one caller gets it until it expires; a refresh's time is kept under
 * {@code fresh:<origin>} for the freshness threshold.
 */
public final class ValkeyRefreshCoordination implements RefreshCoordination {

  private final StringRedisTemplate valkey;
  private final Duration claimTtl;
  private final Duration freshnessThreshold;
  private final ValkeyFailures failures;

  public ValkeyRefreshCoordination(
      StringRedisTemplate valkey,
      Duration claimTtl,
      Duration freshnessThreshold,
      ValkeyFailures failures) {
    this.valkey = valkey;
    this.claimTtl = claimTtl;
    this.freshnessThreshold = freshnessThreshold;
    this.failures = failures;
  }

  /** Nothing when Valkey fails the read. */
  @Override
  public Optional<Instant> refreshedAt(Airport origin) {
    Optional<String> mark =
        failures.call(
            () -> Optional.ofNullable(valkey.opsForValue().get(freshKey(origin))), Optional::empty);
    return mark.map(Instant::parse);
  }

  /** False when Valkey fails the claim. */
  @Override
  public boolean claim(Airport origin) {
    return failures.call(
        () ->
            Boolean.TRUE.equals(
                valkey.opsForValue().setIfAbsent(claimKey(origin), "claimed", claimTtl)),
        () -> false);
  }

  /** Records nothing when Valkey fails the write. */
  @Override
  public void refreshed(Airport origin, Instant at) {
    failures.run(
        () -> valkey.opsForValue().set(freshKey(origin), at.toString(), freshnessThreshold));
  }

  private static String claimKey(Airport origin) {
    return "refresh:" + origin.code();
  }

  private static String freshKey(Airport origin) {
    return "fresh:" + origin.code();
  }
}
