package com.flightaggregator.adapter.provider;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

/**
 * How one provider is called and downloaded (§6.1). {@code requestTimeout} bounds one request,
 * {@code downloadTimeout} the whole download of all its pages; the production values are 2.5 s, 60
 * s, 3 attempts, 200 ms, 1000 records per page, 200 pages and 8 MB.
 */
public record ResilienceSettings(
    Duration requestTimeout,
    Duration downloadTimeout,
    int maxAttempts,
    Duration firstBackoff,
    int pageSize,
    int maxPages,
    int maxBodyBytes) {

  /** Each failure names its component: a zero timeout would silently mean "no timeout". */
  public ResilienceSettings {
    requirePositive(requireNonNull(requestTimeout, "requestTimeout"), "requestTimeout");
    requirePositive(requireNonNull(downloadTimeout, "downloadTimeout"), "downloadTimeout");
    requirePositive(requireNonNull(firstBackoff, "firstBackoff"), "firstBackoff");
    requirePositive(maxAttempts, "maxAttempts");
    requirePositive(pageSize, "pageSize");
    requirePositive(maxPages, "maxPages");
    if (maxBodyBytes < 1 || maxBodyBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "maxBodyBytes must be from 1 to " + (Integer.MAX_VALUE - 1) + ": " + maxBodyBytes);
    }
  }

  private static void requirePositive(Duration value, String component) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(component + " must be positive: " + value);
    }
  }

  private static void requirePositive(int value, String component) {
    if (value < 1) {
      throw new IllegalArgumentException(component + " must be positive: " + value);
    }
  }
}
