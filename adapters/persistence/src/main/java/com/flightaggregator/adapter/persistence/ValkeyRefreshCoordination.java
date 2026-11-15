package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.domain.Airport;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Refresh coordination in Valkey (§6.2): a claim is {@code SET refresh:<origin> NX} with the
 * claim's time to live, so one caller gets it until it expires; a refresh's time is kept under
 * {@code fresh:<origin>} for the freshness threshold. A mark this version cannot read, such as one
 * another version wrote, is no refresh and a {@code WARN}: Valkey is a cache, so nothing it holds
 * may fail a search. Many at once, as after a change of format, are one {@code WARN} per freshness
 * threshold, the longest such a mark can still be found.
 */
public final class ValkeyRefreshCoordination implements RefreshCoordination {

  private static final Logger LOG = LoggerFactory.getLogger(ValkeyRefreshCoordination.class);

  private final StringRedisTemplate valkey;
  private final Duration claimTtl;
  private final Duration freshnessThreshold;
  private final ValkeyFailures failures;
  private final EpisodeWarning unreadableMarks;

  public ValkeyRefreshCoordination(
      StringRedisTemplate valkey,
      Duration claimTtl,
      Duration freshnessThreshold,
      ValkeyFailures failures,
      Clock clock) {
    this.valkey = valkey;
    this.claimTtl = claimTtl;
    this.freshnessThreshold = freshnessThreshold;
    this.failures = failures;
    this.unreadableMarks =
        new EpisodeWarning(
            LOG,
            "An origin's refresh mark cannot be read, so the origin counts as not refreshed",
            freshnessThreshold,
            clock);
  }

  /** Nothing when Valkey fails the read, or when the mark cannot be read. */
  @Override
  public Optional<Instant> refreshedAt(Airport origin) {
    Optional<String> mark =
        failures.call(
            () -> Optional.ofNullable(valkey.opsForValue().get(freshKey(origin))), Optional::empty);
    return mark.flatMap(this::instant);
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

  private Optional<Instant> instant(String mark) {
    try {
      return Optional.of(Instant.parse(mark));
    } catch (DateTimeParseException unreadable) {
      // Neither the mark nor the failure, which quotes it, is logged.
      unreadableMarks.warn();
      return Optional.empty();
    }
  }

  private static String claimKey(Airport origin) {
    return "refresh:" + origin.code();
  }

  private static String freshKey(Airport origin) {
    return "fresh:" + origin.code();
  }
}
