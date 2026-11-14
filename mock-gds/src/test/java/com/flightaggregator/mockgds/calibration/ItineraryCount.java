package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

/** Counts of the itineraries an {@link ItineraryEnumerator} yields, by number of stops and by shape. */
final class ItineraryCount {

    private static final int ONE_STOP_LEGS = 2;

    private final long[] byLegs = new long[4];
    /** Single-airline itineraries by number of legs, then by airline code. */
    private final Map<Integer, Map<String, Long>> singleAirlineByLegs = new TreeMap<>();
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
            singleAirlineByLegs.computeIfAbsent(itinerary.size(), legs -> new TreeMap<>())
                    .merge(itinerary.getFirst().airline(), 1L, Long::sum);
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
        return sum(singleAirlineByCarrier());
    }

    /** Itineraries whose legs are all operated by the same airline, by airline code. */
    Map<String, Long> singleAirlineByCarrier() {
        return singleAirlineByCarrier(legs -> true);
    }

    /** Direct and one-stop itineraries whose legs are all operated by the same airline, by airline code. */
    Map<String, Long> singleAirlineUpToOneStopByCarrier() {
        return singleAirlineByCarrier(legs -> legs <= ONE_STOP_LEGS);
    }

    /** Share of the itineraries whose legs are all operated by the same airline. */
    double singleAirlineShare() {
        return (double) singleAirline() / total();
    }

    /** Share of the direct and one-stop itineraries whose legs are all operated by the same airline. */
    double singleAirlineShareUpToOneStop() {
        return (double) sum(singleAirlineUpToOneStopByCarrier()) / (direct() + oneStop());
    }

    private Map<String, Long> singleAirlineByCarrier(IntPredicate legs) {
        return singleAirlineByLegs.entrySet().stream()
                .filter(countsOfLegs -> legs.test(countsOfLegs.getKey()))
                .flatMap(countsOfLegs -> countsOfLegs.getValue().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum, TreeMap::new));
    }

    private static long sum(Map<String, Long> countsByCarrier) {
        return countsByCarrier.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Origin-destination pairs that the itineraries connect only with two stops. */
    Set<String> pairsReachableOnlyWithTwoStops() {
        var pairs = new HashSet<>(twoStopPairs);
        pairs.removeAll(directPairs);
        pairs.removeAll(oneStopPairs);
        return pairs;
    }
}
