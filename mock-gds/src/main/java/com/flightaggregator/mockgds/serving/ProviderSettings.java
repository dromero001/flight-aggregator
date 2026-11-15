package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.model.Provider;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Per-provider gate settings, bound from {@code mockgds.providers.<provider>.*}. */
@ConfigurationProperties("mockgds.providers")
public record ProviderSettings(Entry alpha, Entry beta, Entry gamma) {

  public ProviderSettings {
    require(alpha, "alpha");
    require(beta, "beta");
    require(gamma, "gamma");
  }

  /** The settings of one provider. */
  Entry forProvider(Provider provider) {
    return switch (provider) {
      case ALPHA -> alpha;
      case BETA -> beta;
      case GAMMA -> gamma;
    };
  }

  private static void require(Entry entry, String provider) {
    if (entry == null || entry.apiKey() == null || entry.apiKey().isBlank()) {
      throw new IllegalArgumentException("mockgds.providers." + provider + ".api-key must be set");
    }
    if (entry.latencyMin().isNegative()) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".latency-min must not be negative");
    }
    if (entry.latencyMin().compareTo(entry.latencyMax()) > 0) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".latency-min must not exceed latency-max");
    }
    if (!(entry.errorRate() >= 0 && entry.errorRate() <= 1)) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".error-rate must be within [0, 1]");
    }
  }

  /** One provider's key, latency range and error rate. */
  public record Entry(
      String apiKey,
      @DefaultValue("100ms") Duration latencyMin,
      @DefaultValue("5000ms") Duration latencyMax,
      @DefaultValue("0.05") double errorRate) {

    /** Redacts the key, so logging the settings can never leak it. */
    @Override
    public String toString() {
      return "Entry[apiKey=***, latencyMin=%s, latencyMax=%s, errorRate=%s]"
          .formatted(latencyMin, latencyMax, errorRate);
    }
  }
}
