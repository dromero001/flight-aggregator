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
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The three provider adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvidersProperties.class)
class ProvidersConfig {

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
   * Builds one provider from the provider alone: its endpoint, its circuit breaker, its download
   * and that download's time limiter all derive from it, so no part of one provider can be wired to
   * another.
   */
  private static WiredProvider wire(
      Provider provider, ProvidersProperties properties, Clock clock) {
    ResilienceSettings settings = properties.resilience();
    ProviderEndpoint endpoint = properties.endpoint(provider);
    TimeLimiter timeLimiter = PagedDownload.timeLimiterFor(settings);
    PagedDownload download = new PagedDownload(provider, settings, timeLimiter);
    CircuitBreaker circuitBreaker = ProviderHttp.circuitBreakerFor(provider);
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
