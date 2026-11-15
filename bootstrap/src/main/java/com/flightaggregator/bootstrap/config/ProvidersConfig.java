package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.adapter.provider.alpha.AlphaProvider;
import com.flightaggregator.adapter.provider.beta.BetaProvider;
import com.flightaggregator.adapter.provider.gamma.GammaProvider;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The three provider adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvidersProperties.class)
class ProvidersConfig {

  /**
   * Each provider's breaker (plan 4): over its last 10 calls, it opens at 50% failures, stays open
   * 60 s, then lets 3 trial calls through. It counts only what {@link
   * ProviderHttp#countsAgainstTheBreaker} counts: a server error, a timeout or an I/O error. Every
   * other failure is ignored rather than counted as a success, so a wrong key neither opens the
   * breaker nor closes it.
   */
  private static final CircuitBreakerConfig BREAKER =
      CircuitBreakerConfig.custom()
          .slidingWindow(10, 10, SlidingWindowType.COUNT_BASED)
          .failureRateThreshold(50)
          .waitDurationInOpenState(Duration.ofSeconds(60))
          .permittedNumberOfCallsInHalfOpenState(3)
          .recordException(ProviderHttp::countsAgainstTheBreaker)
          .ignoreException(Predicate.not(ProviderHttp::countsAgainstTheBreaker))
          .build();

  @Bean
  WiredProvider alpha(ProvidersProperties properties, Clock clock) {
    return wire(Provider.ALPHA, properties, clock);
  }

  @Bean
  WiredProvider beta(ProvidersProperties properties, Clock clock) {
    return wire(Provider.BETA, properties, clock);
  }

  @Bean
  WiredProvider gamma(ProvidersProperties properties, Clock clock) {
    return wire(Provider.GAMMA, properties, clock);
  }

  /**
   * Builds one provider from the provider alone: its endpoint, its download and that download's
   * time limiter all derive from it, so no part of one provider can be wired to another.
   */
  private static WiredProvider wire(
      Provider provider, ProvidersProperties properties, Clock clock) {
    ResilienceSettings settings = properties.resilience();
    ProviderEndpoint endpoint = properties.endpoint(provider);
    TimeLimiter timeLimiter = PagedDownload.timeLimiterFor(settings);
    PagedDownload download = new PagedDownload(provider, settings, timeLimiter);
    CircuitBreaker circuitBreaker = CircuitBreaker.of(provider.name(), BREAKER);
    ProviderHttp http = new ProviderHttp(endpoint, settings, circuitBreaker, clock);
    FlightProvider adapter =
        switch (provider) {
          case ALPHA -> new AlphaProvider(http, download, settings);
          case BETA -> new BetaProvider(http, download, settings);
          case GAMMA -> new GammaProvider(http, download, settings);
        };
    return new WiredProvider(adapter, endpoint, settings, circuitBreaker, timeLimiter, download);
  }
}
