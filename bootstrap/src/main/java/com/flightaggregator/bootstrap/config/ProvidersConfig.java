package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.provider.PagedProvider;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.bootstrap.observability.CountedProviders;
import com.flightaggregator.bootstrap.observability.CountedRetries;
import com.flightaggregator.bootstrap.observability.LoggedBreakerTransitions;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

/**
 * The three provider adapters, each built from its own endpoint, and their circuit breakers: one
 * per provider, named after it, in one registry with the production configuration (§6.5). Each
 * adapter is proxied on its own for its {@code @ConcurrencyLimit}, so each provider has its own
 * limit (Q9). The proxy is a class proxy, Boot's default: an interface proxy would read the limit
 * of {@code FlightProvider}'s methods, not of the adapter's, and so would limit {@code provider()}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvidersProperties.class)
@EnableResilientMethods
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

  /** Every provider call counted, after its limit's proxy (§6.10). */
  @Bean
  static CountedProviders countedProviders(ObjectProvider<MeterRegistry> meters) {
    return new CountedProviders(meters);
  }

  @Bean
  FlightProvider alpha(
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock,
      RestClient.Builder http,
      ThreadFactory contextThreads) {
    return provider(
        Provider.ALPHA, properties, circuitBreakers, meters, clock, http, contextThreads);
  }

  @Bean
  FlightProvider beta(
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock,
      RestClient.Builder http,
      ThreadFactory contextThreads) {
    return provider(
        Provider.BETA, properties, circuitBreakers, meters, clock, http, contextThreads);
  }

  @Bean
  FlightProvider gamma(
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock,
      RestClient.Builder http,
      ThreadFactory contextThreads) {
    return provider(
        Provider.GAMMA, properties, circuitBreakers, meters, clock, http, contextThreads);
  }

  /**
   * Every part, its retries' counter included, is named after the endpoint's provider. Its requests
   * are built from Boot's {@code RestClient.Builder}, which observes each one and sends the trace
   * as W3C {@code traceparent}, and its page loop runs on a thread that carries the caller's trace
   * (§6.10).
   */
  private static FlightProvider provider(
      Provider provider,
      ProvidersProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meters,
      Clock clock,
      RestClient.Builder http,
      ThreadFactory contextThreads) {
    ProviderEndpoint endpoint = properties.endpoint(provider);
    return new PagedProvider(
        endpoint,
        properties.resilience(),
        circuitBreakers,
        new CountedRetries(meters, endpoint.provider()),
        clock,
        http,
        contextThreads);
  }
}
