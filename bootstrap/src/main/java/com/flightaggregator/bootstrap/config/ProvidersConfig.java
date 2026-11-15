package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.provider.PagedProvider;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.bootstrap.observability.CountedRetries;
import com.flightaggregator.bootstrap.observability.LoggedBreakerTransitions;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three provider adapters, each built from its own endpoint, and their circuit breakers: one
 * per provider, named after it, in one registry with the production configuration (§6.5).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvidersProperties.class)
class ProvidersConfig {

  /**
   * Each breaker's state and calls are meters, tagged with its name, the provider's, and each of
   * its state transitions is a warning (§6.10). Both watch the registry before any breaker is
   * created.
   */
  @Bean
  CircuitBreakerRegistry circuitBreakers(MeterRegistry meters) {
    CircuitBreakerRegistry circuitBreakers =
        CircuitBreakerRegistry.of(ProviderHttp.circuitBreakerConfig());
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(meters);
    LoggedBreakerTransitions.of(circuitBreakers);
    return circuitBreakers;
  }

  @Bean
  FlightProvider alpha(
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock) {
    return provider(Provider.ALPHA, properties, circuitBreakers, meters, clock);
  }

  @Bean
  FlightProvider beta(
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock) {
    return provider(Provider.BETA, properties, circuitBreakers, meters, clock);
  }

  @Bean
  FlightProvider gamma(
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock) {
    return provider(Provider.GAMMA, properties, circuitBreakers, meters, clock);
  }

  /** Every part, its retries' counter included, is named after the endpoint's provider. */
  private static FlightProvider provider(
      Provider provider,
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock) {
    ProviderEndpoint endpoint = properties.endpoint(provider);
    return new PagedProvider(
        endpoint,
        properties.resilience(),
        circuitBreakers,
        new CountedRetries(meters, endpoint.provider()),
        clock);
  }
}
