package com.flightaggregator.bootstrap.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Search's settings, bound from {@code search.*}: how long a page is cached and an origin counts as
 * fresh (§6.4), how long a refresh claim holds (Q9), and how long Valkey is skipped after it fails
 * a command (plan 5, the user's decision of 2026-11-15). Each is required and above zero, so {@code
 * application.yaml} is their one source: startup stops otherwise, naming the setting, instead of
 * the application starting with a store that fails at its first use. A setting under {@code search}
 * that names none of these stops startup too, as under {@code providers}: a misspelt key would
 * otherwise leave the value it meant to change.
 */
@ConfigurationProperties(prefix = "search", ignoreUnknownFields = false)
record SearchProperties(
    Duration cacheTtl, Duration freshnessThreshold, Duration refreshClaimTtl, Duration valkeySkip) {

  /** The shortest wait Valkey's circuit breaker can be built with: it waits whole milliseconds. */
  private static final Duration SHORTEST_VALKEY_SKIP = Duration.ofMillis(1);

  SearchProperties {
    require(cacheTtl, "cache-ttl");
    require(freshnessThreshold, "freshness-threshold");
    require(refreshClaimTtl, "refresh-claim-ttl");
    require(valkeySkip, "valkey-skip");
    if (valkeySkip.compareTo(SHORTEST_VALKEY_SKIP) < 0) {
      throw new IllegalArgumentException(
          "search.valkey-skip must be at least 1 ms, not " + valkeySkip);
    }
  }

  /**
   * A missing setting binds as null. Zero or less would make Valkey refuse every write that sets it
   * as a time to live, or, for {@code valkey-skip}, Resilience4j refuse to build Valkey's breaker.
   */
  private static void require(Duration setting, String name) {
    if (setting == null || !setting.isPositive()) {
      throw new IllegalArgumentException(
          "search." + name + " must be set to more than zero, not " + setting);
    }
  }
}
