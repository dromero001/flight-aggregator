package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.schedule.ScheduleParameters;
import java.time.Duration;
import java.util.Objects;

/**
 * Size and shape of the generated dataset.
 *
 * @param seed seed of every random decision
 * @param windowDays length of the rolling window, starting on the clock's UTC date
 * @param maxDailyFlightsPerHubRoute each route to or from a hub gets between one and this many
 *     flights a day
 * @param maxDailyFlightsPerNonHubRoute each route between two airports that are not hubs gets
 *     between one and this many flights a day
 * @param operatingProbability probability that a scheduled flight operates on a given date
 * @param twoProviderShare share of legs sold by exactly two providers
 * @param threeProviderShare share of legs sold by all three providers
 * @param malformedShare share of each provider's records served malformed instead of valid
 * @param priceBucket how long a quoted price stays the same before it drifts
 */
public record GeneratorConfig(
    long seed,
    int windowDays,
    int maxDailyFlightsPerHubRoute,
    int maxDailyFlightsPerNonHubRoute,
    double operatingProbability,
    double twoProviderShare,
    double threeProviderShare,
    double malformedShare,
    Duration priceBucket) {

  public GeneratorConfig {
    if (windowDays < 1) {
      throw new IllegalArgumentException("windowDays must be at least 1");
    }
    requireShare("twoProviderShare", twoProviderShare);
    requireShare("threeProviderShare", threeProviderShare);
    requireShare("malformedShare", malformedShare);
    if (twoProviderShare + threeProviderShare > 1) {
      throw new IllegalArgumentException("twoProviderShare + threeProviderShare must not exceed 1");
    }
    Objects.requireNonNull(priceBucket);
  }

  /** The parameters used by the mock providers, calibrated to about one million itineraries. */
  public static GeneratorConfig defaults() {
    long seed = 20261116L;
    int windowDays = 30;
    int maxDailyFlightsPerHubRoute = 4;
    int maxDailyFlightsPerNonHubRoute = 2;
    double operatingProbability = 0.345;
    double twoProviderShare = 0.25;
    double threeProviderShare = 0.10;
    double malformedShare = 0.01;
    var priceBucket = Duration.ofMinutes(15);
    return new GeneratorConfig(
        seed,
        windowDays,
        maxDailyFlightsPerHubRoute,
        maxDailyFlightsPerNonHubRoute,
        operatingProbability,
        twoProviderShare,
        threeProviderShare,
        malformedShare,
        priceBucket);
  }

  ScheduleParameters scheduleParameters() {
    return new ScheduleParameters(
        seed, maxDailyFlightsPerHubRoute, maxDailyFlightsPerNonHubRoute, operatingProbability);
  }

  private static void requireShare(String name, double share) {
    if (share < 0 || share > 1) {
      throw new IllegalArgumentException(name + " must be between 0 and 1: " + share);
    }
  }
}
