package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.application.port.out.ProviderBreakers;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Each provider's breaker as the registry holds it (plan 4: one per provider, by its name), read
 * live on every call, so the state is this instance's at that moment.
 */
public final class RegistryProviderBreakers implements ProviderBreakers {

  private final CircuitBreakerRegistry breakers;

  public RegistryProviderBreakers(CircuitBreakerRegistry breakers) {
    this.breakers = breakers;
  }

  /** A breaker forced open refuses calls; one disabled or only metering lets them all through. */
  @Override
  public BreakerState state(Provider provider) {
    return switch (breakers.circuitBreaker(provider.name()).getState()) {
      case OPEN, FORCED_OPEN -> BreakerState.OPEN;
      case HALF_OPEN -> BreakerState.HALF_OPEN;
      case CLOSED, DISABLED, METRICS_ONLY -> BreakerState.CLOSED;
    };
  }
}
