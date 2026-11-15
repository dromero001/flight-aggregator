package com.flightaggregator.application.port.in;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * One provider's row on the provider-status screen (§6.8): its health (Q8-A), its breaker's state
 * on this instance, the newest quote among its stored offers, how many of them the last run used
 * ({@code legs}) and what it discarded; {@code stale} when the last run kept its offers from an
 * earlier one.
 */
public record ProviderStatus(
    Provider provider,
    Health health,
    BreakerState breaker,
    Optional<Instant> newestQuotedAt,
    int legs,
    Map<DiscardReason, Integer> discarded,
    boolean stale) {

  public ProviderStatus {
    discarded = Map.copyOf(discarded);
  }

  /**
   * Q8-A: {@code DOWN} when the last run kept the provider's offers or its breaker is open, {@code
   * DEGRADED} while its breaker is half-open, otherwise {@code UP}.
   */
  public enum Health {
    UP,
    DEGRADED,
    DOWN
  }
}
