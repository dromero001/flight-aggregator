package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RegistryProviderBreakersTest {

  /**
   * Each provider's breaker is the registry's of its name (the one {@code ProviderHttp} calls
   * through), read live. A breaker forced open refuses calls as an open one does; one disabled or
   * only metering lets every call through, as a closed one does.
   */
  @ParameterizedTest
  @CsvSource({
    "CLOSED, CLOSED",
    "OPEN, OPEN",
    "HALF_OPEN, HALF_OPEN",
    "FORCED_OPEN, OPEN",
    "DISABLED, CLOSED",
    "METRICS_ONLY, CLOSED"
  })
  void readsEachProvidersBreakerFromTheRegistry(CircuitBreaker.State state, BreakerState read) {
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    CircuitBreaker beta = registry.circuitBreaker(Provider.BETA.name());
    switch (state) {
      case CLOSED -> {}
      case OPEN -> beta.transitionToOpenState();
      case HALF_OPEN -> {
        beta.transitionToOpenState();
        beta.transitionToHalfOpenState();
      }
      case FORCED_OPEN -> beta.transitionToForcedOpenState();
      case DISABLED -> beta.transitionToDisabledState();
      case METRICS_ONLY -> beta.transitionToMetricsOnlyState();
    }
    RegistryProviderBreakers breakers = new RegistryProviderBreakers(registry);

    assertThat(breakers.state(Provider.BETA)).isEqualTo(read);
    assertThat(breakers.state(Provider.ALPHA)).isEqualTo(BreakerState.CLOSED);
  }
}
