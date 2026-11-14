package com.flightaggregator.mockgds.schedule;

import com.flightaggregator.mockgds.network.Route;

/**
 * Size parameters of the flight schedule.
 *
 * @param seed                          seed of every random decision
 * @param maxDailyFlightsPerHubRoute    each route to or from a hub gets between one and this many flights a day
 * @param maxDailyFlightsPerNonHubRoute each route between two airports that are not hubs gets between one and this
 *                                      many flights a day
 * @param operatingProbability          probability that a scheduled flight operates on a given date
 */
public record ScheduleParameters(long seed, int maxDailyFlightsPerHubRoute, int maxDailyFlightsPerNonHubRoute,
                                 double operatingProbability) {

    public ScheduleParameters {
        requireAtLeastOne("maxDailyFlightsPerHubRoute", maxDailyFlightsPerHubRoute);
        requireAtLeastOne("maxDailyFlightsPerNonHubRoute", maxDailyFlightsPerNonHubRoute);
        if (operatingProbability <= 0 || operatingProbability > 1) {
            throw new IllegalArgumentException("operatingProbability must be in (0, 1]");
        }
    }

    /** The most flights a day the route can get: routes to or from a hub have their own maximum. */
    int maxDailyFlights(Route route) {
        return route.servesHub() ? maxDailyFlightsPerHubRoute : maxDailyFlightsPerNonHubRoute;
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1: " + value);
        }
    }
}
