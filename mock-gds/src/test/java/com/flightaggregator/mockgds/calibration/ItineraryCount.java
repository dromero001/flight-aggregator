package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Counts of the itineraries an {@link ItineraryEnumerator} yields, by number of stops and by shape. */
final class ItineraryCount {

    private final long[] byLegs = new long[4];
    private long interlining;
    private long revisitingAnAirport;
    private final Set<String> directPairs = new HashSet<>();
    private final Set<String> oneStopPairs = new HashSet<>();
    private final Set<String> twoStopPairs = new HashSet<>();

    static ItineraryCount of(ItineraryEnumerator enumerator) {
        var count = new ItineraryCount();
        enumerator.forEachItinerary(count::add);
        return count;
    }

    private void add(List<Leg> itinerary) {
        byLegs[itinerary.size()]++;
        if (itinerary.stream().map(Leg::airline).distinct().count() > 1) {
            interlining++;
        }
        // Circular itineraries (e.g. MAD-LHR-MAD-JFK) are valid under §4 and counted above, but they would make
        // a pair look reachable with fewer stops than a traveller would accept, so they are left out of the pair sets.
        if (visitsAnAirportTwice(itinerary)) {
            revisitingAnAirport++;
            return;
        }
        var pair = itinerary.getFirst().origin() + "-" + itinerary.getLast().destination();
        switch (itinerary.size()) {
            case 1 -> directPairs.add(pair);
            case 2 -> oneStopPairs.add(pair);
            default -> twoStopPairs.add(pair);
        }
    }

    long direct() {
        return byLegs[1];
    }

    long oneStop() {
        return byLegs[2];
    }

    long twoStops() {
        return byLegs[3];
    }

    long total() {
        return direct() + oneStop() + twoStops();
    }

    long interlining() {
        return interlining;
    }

    long revisitingAnAirport() {
        return revisitingAnAirport;
    }

    /** Origin-destination pairs that the itineraries connect only with two stops. */
    Set<String> pairsReachableOnlyWithTwoStops() {
        var pairs = new HashSet<>(twoStopPairs);
        pairs.removeAll(directPairs);
        pairs.removeAll(oneStopPairs);
        return pairs;
    }

    private static boolean visitsAnAirportTwice(List<Leg> itinerary) {
        var airports = new HashSet<String>();
        airports.add(itinerary.getFirst().origin());
        return !itinerary.stream().allMatch(leg -> airports.add(leg.destination()));
    }
}
