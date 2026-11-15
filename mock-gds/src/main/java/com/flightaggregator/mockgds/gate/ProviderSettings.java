package com.flightaggregator.mockgds.gate;

import com.flightaggregator.mockgds.model.Provider;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-provider gate settings, bound from {@code mockgds.providers.<provider>.*}. An unknown key
 * stops startup, so a misspelt or outdated setting cannot silently leave its default on.
 */
@ConfigurationProperties(prefix = "mockgds.providers", ignoreUnknownFields = false)
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
    if (entry.fastLatencyMin().isNegative()) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".fast-latency-min must not be negative");
    }
    // The four latency bounds in order: the slow range starts where the fast range ends, or later.
    if (entry.fastLatencyMin().compareTo(entry.fastLatencyMax()) > 0) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".fast-latency-min must not exceed fast-latency-max");
    }
    if (entry.fastLatencyMax().compareTo(entry.slowLatencyMin()) > 0) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".fast-latency-max must not exceed slow-latency-min");
    }
    if (entry.slowLatencyMin().compareTo(entry.slowLatencyMax()) > 0) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".slow-latency-min must not exceed slow-latency-max");
    }
    if (!(entry.slowShare() >= 0 && entry.slowShare() <= 1)) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".slow-share must be within [0, 1]");
    }
    if (!(entry.errorRate() >= 0 && entry.errorRate() <= 1)) {
      throw new IllegalArgumentException(
          "mockgds.providers." + provider + ".error-rate must be within [0, 1]");
    }
  }

  /**
   * One provider's key, latency and error rate. Most requests wait a time in the fast range, and
   * the slow share of them a time in the slow range.
   */
  public record Entry(
      String apiKey,
      @DefaultValue("100ms") Duration fastLatencyMin,
      @DefaultValue("1000ms") Duration fastLatencyMax,
      @DefaultValue("1000ms") Duration slowLatencyMin,
      @DefaultValue("5000ms") Duration slowLatencyMax,
      @DefaultValue("0.1") double slowShare,
      @DefaultValue("0.05") double errorRate) {

    /** Redacts the key, so logging the settings can never leak it. */
    @Override
    public String toString() {
      return ("Entry[apiKey=***, fastLatencyMin=%s, fastLatencyMax=%s, slowLatencyMin=%s,"
              + " slowLatencyMax=%s, slowShare=%s, errorRate=%s]")
          .formatted(
              fastLatencyMin, fastLatencyMax, slowLatencyMin, slowLatencyMax, slowShare, errorRate);
    }
  }
}
