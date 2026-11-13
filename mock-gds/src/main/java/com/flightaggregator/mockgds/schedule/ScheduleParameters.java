package com.flightaggregator.mockgds.schedule;

/**
 * Size parameters of the flight schedule.
 *
 * @param seed                   seed of every random decision
 * @param maxDailyFlightsPerRoute each route gets between one and this many flights a day
 * @param operatingProbability   probability that a scheduled flight operates on a given date
 */
public record ScheduleParameters(long seed, int maxDailyFlightsPerRoute, double operatingProbability) {

    public ScheduleParameters {
        if (maxDailyFlightsPerRoute < 1) {
            throw new IllegalArgumentException("maxDailyFlightsPerRoute must be at least 1");
        }
        if (operatingProbability <= 0 || operatingProbability > 1) {
            throw new IllegalArgumentException("operatingProbability must be in (0, 1]");
        }
    }
}
