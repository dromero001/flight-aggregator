package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.domain.Provider;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The providers' settings, bound from {@code providers.*}: where each provider is and its key, and
 * how every provider is called and downloaded (§6.1, plan 4): one request is bounded by {@code
 * request-timeout}, a whole download by {@code download-timeout}. A setting under {@code providers}
 * that names none of these stops startup, as mock-gds's do: a misspelt key would otherwise leave
 * the default it meant to change. Boot's report of it prints its value, so no secret belongs under
 * a misspelt key.
 *
 * <p>{@code concurrency-limit} has no default here: {@code PagedProvider}'s
 * {@code @ConcurrencyLimit} reads it from the environment, at a provider's first call, so {@code
 * application.yaml} is its one source. It is declared so that the binder accepts it, and checked so
 * that startup stops when it is missing or below 1, instead of every provider call failing.
 */
@ConfigurationProperties(prefix = "providers", ignoreUnknownFields = false)
record ProvidersProperties(
    Endpoint alpha,
    Endpoint beta,
    Endpoint gamma,
    @DefaultValue("2500ms") Duration requestTimeout,
    @DefaultValue("60s") Duration downloadTimeout,
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("1000") int pageSize,
    @DefaultValue("200") int maxPages,
    int concurrencyLimit) {

  /** The wait before the second attempt, doubled before each further one (plan 4). */
  private static final Duration FIRST_BACKOFF = Duration.ofMillis(200);

  /** The largest response body read from a provider: 8 MiB (plan 4). */
  private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

  ProvidersProperties {
    require(alpha, "alpha");
    require(beta, "beta");
    require(gamma, "gamma");
    if (concurrencyLimit < 1) {
      // A missing setting binds as 0.
      throw new IllegalArgumentException(
          "providers.concurrency-limit must be set to 1 or more, not " + concurrencyLimit);
    }
  }

  /** The provider's endpoint, named after it. */
  ProviderEndpoint endpoint(Provider provider) {
    Endpoint endpoint =
        switch (provider) {
          case ALPHA -> alpha;
          case BETA -> beta;
          case GAMMA -> gamma;
        };
    return new ProviderEndpoint(provider, endpoint.baseUrl(), endpoint.apiKey());
  }

  /** How each provider is called and downloaded; it refuses a value out of range. */
  ResilienceSettings resilience() {
    return new ResilienceSettings(
        requestTimeout,
        downloadTimeout,
        maxAttempts,
        FIRST_BACKOFF,
        pageSize,
        maxPages,
        MAX_BODY_BYTES);
  }

  /** A provider without any setting has neither a base URL nor a key. */
  private static void require(Endpoint endpoint, String provider) {
    if (endpoint == null) {
      String prefix = "providers." + provider;
      throw new IllegalArgumentException(
          prefix + ".base-url and " + prefix + ".api-key must be set");
    }
  }

  /** Where one provider is and its key. */
  record Endpoint(URI baseUrl, String apiKey) {

    /** Each failure names its property; the binder adds the provider's prefix. */
    Endpoint {
      if (baseUrl == null) {
        throw new IllegalArgumentException("base-url must be set");
      }
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("api-key must be set");
      }
    }

    /** Redacts the key, so logging the settings can never leak it (§6.6). */
    @Override
    public String toString() {
      return "Endpoint[baseUrl=" + baseUrl + ", apiKey=***]";
    }
  }
}
