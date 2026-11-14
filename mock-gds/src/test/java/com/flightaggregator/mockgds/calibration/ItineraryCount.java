package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Counts of the itineraries an {@link ItineraryEnumerator} yields, by number of stops and by shape. */
final class ItineraryCount {

    private final long[] byLegs = new long[4];
    private final Map<String, Long> singleAirlineByCarrier = new TreeMap<>();
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
        if (itinerary.stream().map(Leg::airline).distinct().count() == 1) {
            singleAirlineByCarrier.merge(itinerary.getFirst().airline(), 1L, Long::sum);
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

    /** Itineraries that mix airlines. */
    long interlining() {
        return total() - singleAirline();
    }

    long singleAirline() {
        return singleAirlineByCarrier.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Itineraries whose legs are all operated by the same airline, by airline code. */
    Map<String, Long> singleAirlineByCarrier() {
        return Collections.unmodifiableMap(singleAirlineByCarrier);
    }

    /** Share of the itineraries whose legs are all operated by the same airline. */
    double singleAirlineShare() {
        return (double) singleAirline() / total();
    }

    /** Origin-destination pairs that the itineraries connect only with two stops. */
    Set<String> pairsReachableOnlyWithTwoStops() {
        var pairs = new HashSet<>(twoStopPairs);
        pairs.removeAll(directPairs);
        pairs.removeAll(oneStopPairs);
        return pairs;
    }
}
