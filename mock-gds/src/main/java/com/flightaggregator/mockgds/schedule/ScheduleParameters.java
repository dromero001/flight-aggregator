package com.flightaggregator.mockgds.schedule;

/**
 * Size parameters of the flight schedule.
 *
 * @param seed                           seed of every random decision
 * @param maxDailyFlightsPerNetworkRoute each network carrier's route gets between one and this many flights a day
 * @param maxDailyFlightsPerLowCostRoute each low-cost carrier's route gets between one and this many flights a day
 * @param operatingProbability           probability that a scheduled flight operates on a given date
 */
public record ScheduleParameters(long seed, int maxDailyFlightsPerNetworkRoute, int maxDailyFlightsPerLowCostRoute,
                                 double operatingProbability) {

    public ScheduleParameters {
        requireAtLeastOne("maxDailyFlightsPerNetworkRoute", maxDailyFlightsPerNetworkRoute);
        requireAtLeastOne("maxDailyFlightsPerLowCostRoute", maxDailyFlightsPerLowCostRoute);
        if (operatingProbability <= 0 || operatingProbability > 1) {
            throw new IllegalArgumentException("operatingProbability must be in (0, 1]");
        }
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1: " + value);
        }
    }
}
