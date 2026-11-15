package com.flightaggregator.mockgds.gate;

import com.flightaggregator.mockgds.model.Provider;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-provider gate settings, bound from {@code mockgds.providers.<provider>.*}. An unknown key in
 * a properties file or a command-line argument stops startup, so a misspelt or outdated setting
 * there cannot silently leave its default on. Boot does not check environment variables or JVM
 * system properties this way; {@link SettingsConfig} checks the environment's {@code
 * MOCKGDS_PROVIDERS_} variables. Boot's report of an unknown setting prints its value, so an API
 * key given under a misspelt name ({@code api-kye}) is printed at startup.
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

  /** A provider without any setting has no key either. */
  private static void require(Entry entry, String provider) {
    if (entry == null) {
      throw new IllegalArgumentException("mockgds.providers." + provider + ".api-key must be set");
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

    /** Each failure names its property; the binder adds the provider's prefix. */
    public Entry {
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("api-key must be set");
      }
      requireRange("fast-latency", fastLatencyMin, fastLatencyMax);
      requireRange("slow-latency", slowLatencyMin, slowLatencyMax);
      requireShare("slow-share", slowShare);
      requireShare("error-rate", errorRate);
    }

    private static void requireRange(String name, Duration min, Duration max) {
      if (min.isNegative()) {
        throw new IllegalArgumentException(name + "-min must not be negative: " + min);
      }
      if (min.compareTo(max) > 0) {
        throw new IllegalArgumentException(
            name + "-min must not exceed " + name + "-max: " + min + " > " + max);
      }
    }

    private static void requireShare(String name, double share) {
      if (!(share >= 0 && share <= 1)) {
        throw new IllegalArgumentException(name + " must be within [0, 1]: " + share);
      }
    }

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
