package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.provider.PagedProvider;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

  @Bean
  CircuitBreakerRegistry circuitBreakers() {
    return CircuitBreakerRegistry.of(ProviderHttp.circuitBreakerConfig());
  }

  @Bean
  FlightProvider alpha(
      ProvidersProperties properties, CircuitBreakerRegistry circuitBreakers, Clock clock) {
    return provider(Provider.ALPHA, properties, circuitBreakers, clock);
  }

  @Bean
  FlightProvider beta(
      ProvidersProperties properties, CircuitBreakerRegistry circuitBreakers, Clock clock) {
    return provider(Provider.BETA, properties, circuitBreakers, clock);
  }

  @Bean
  FlightProvider gamma(
      ProvidersProperties properties, CircuitBreakerRegistry circuitBreakers, Clock clock) {
    return provider(Provider.GAMMA, properties, circuitBreakers, clock);
  }

  private static FlightProvider provider(
      Provider provider,
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      Clock clock) {
    return new PagedProvider(
        properties.endpoint(provider), properties.resilience(), circuitBreakers, clock);
  }
}
