package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Counts pairs of sellable legs by why they do or do not connect (§4): valid, shorter than the minimum connection
 * time, a layover longer than 24 hours (up to 48 hours, to keep the count finite), or a timing that would work but
 * at a different airport.
 */
record ConnectionCensus(long valid, long belowMinimumConnectionTime, long layoverOverTwentyFourHours,
                        long differentAirport) {

    private static final Duration LONG_LAYOVER_HORIZON = Duration.ofHours(48);

    static ConnectionCensus of(List<Leg> sellableLegs) {
        long[] departures = sellableLegs.stream().mapToLong(leg -> leg.departure().toEpochSecond()).sorted().toArray();
        var departuresByAirport = sellableLegs.stream().collect(Collectors.groupingBy(Leg::origin));
        long valid = 0;
        long tooShort = 0;
        long tooLong = 0;
        long anyAirportInWindow = 0;
        for (var inbound : sellableLegs) {
            long arrival = inbound.arrival().toEpochSecond();
            anyAirportInWindow += countBetween(departures,
                    arrival + ConnectionRules.MINIMUM_CONNECTION_TIME.toSeconds(),
                    arrival + ConnectionRules.MAXIMUM_LAYOVER.toSeconds());
            for (var outbound : departuresByAirport.getOrDefault(inbound.destination(), List.of())) {
                var layover = ConnectionRules.layover(inbound, outbound);
                if (layover.isNegative() || layover.compareTo(LONG_LAYOVER_HORIZON) > 0) {
                    continue;
                }
                if (layover.compareTo(ConnectionRules.MINIMUM_CONNECTION_TIME) < 0) {
                    tooShort++;
                } else if (layover.compareTo(ConnectionRules.MAXIMUM_LAYOVER) > 0) {
                    tooLong++;
                } else {
                    valid++;
                }
            }
        }
        return new ConnectionCensus(valid, tooShort, tooLong, anyAirportInWindow - valid);
    }

    private static long countBetween(long[] sorted, long fromInclusive, long toInclusive) {
        return lowerBound(sorted, toInclusive + 1) - lowerBound(sorted, fromInclusive);
    }

    /** Index of the first element not less than {@code value}. */
    private static int lowerBound(long[] sorted, long value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
