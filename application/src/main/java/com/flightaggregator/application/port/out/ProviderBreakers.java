package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Provider;

/**
 * The state of each provider's circuit breaker (§6.5), read live: a breaker is this instance's, so
 * it is never stored.
 */
public interface ProviderBreakers {

  BreakerState state(Provider provider);

  /** Closed lets calls through, open refuses them, half-open lets a few probe the provider. */
  enum BreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
  }
}
