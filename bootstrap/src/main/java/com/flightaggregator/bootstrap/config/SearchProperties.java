package com.flightaggregator.bootstrap.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Search's settings, bound from {@code search.*}: how long a page is cached and an origin counts as
 * fresh (§6.4), and how long a refresh claim holds (Q9). Each is required and above zero, so {@code
 * application.yaml} is their one source: startup stops otherwise, naming the setting, instead of
 * the application starting with a store that fails at its first use. A setting under {@code search}
 * that names none of these stops startup too, as under {@code providers}: a misspelt key would
 * otherwise leave the value it meant to change.
 */
@ConfigurationProperties(prefix = "search", ignoreUnknownFields = false)
record SearchProperties(Duration cacheTtl, Duration freshnessThreshold, Duration refreshClaimTtl) {

  SearchProperties {
    require(cacheTtl, "cache-ttl");
    require(freshnessThreshold, "freshness-threshold");
    require(refreshClaimTtl, "refresh-claim-ttl");
  }

  /** A missing setting binds as null; zero or less would make Valkey refuse every write. */
  private static void require(Duration setting, String name) {
    if (setting == null || !setting.isPositive()) {
      throw new IllegalArgumentException(
          "search." + name + " must be set to more than zero, not " + setting);
    }
  }
}
