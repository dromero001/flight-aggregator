package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Test-only stand-in for the future itinerary builder: enumerates every itinerary of one to three legs that §4 of the
 * design accepts, after cross-provider deduplication (cheapest offer wins) and without departed legs.
 */
final class ItineraryEnumerator {

    private static final int MAX_LEGS = 3;

    private final Map<String, List<Leg>> departuresByAirport;

    private ItineraryEnumerator(List<Leg> sellableLegs) {
        this.departuresByAirport = sellableLegs.stream().collect(Collectors.groupingBy(Leg::origin,
                Collectors.collectingAndThen(Collectors.toList(), legs -> legs.stream()
                        .sorted(Comparator.comparing(Leg::departure)).toList())));
    }

    static ItineraryEnumerator of(List<Leg> offers, Instant now) {
        return new ItineraryEnumerator(sellableLegs(offers, now));
    }

    /** One leg per key, the cheapest offer, excluding legs that departed at or before {@code now}. */
    static List<Leg> sellableLegs(List<Leg> offers, Instant now) {
        BinaryOperator<Leg> cheapest = BinaryOperator.minBy(Comparator.comparing(Leg::priceEur, BigDecimal::compareTo));
        return offers.stream()
                .filter(leg -> leg.departure().toInstant().isAfter(now))
                .collect(Collectors.toMap(Leg::key, Function.identity(), cheapest))
                .values().stream()
                .sorted(Comparator.comparing(Leg::departure).thenComparing(leg -> leg.key().toString()))
                .toList();
    }

    void forEachItinerary(Consumer<List<Leg>> visitor) {
        departuresByAirport.values().stream().flatMap(List::stream)
                .forEach(first -> extend(List.of(first), visitor));
    }

    private void extend(List<Leg> itinerary, Consumer<List<Leg>> visitor) {
        visitor.accept(itinerary);
        if (itinerary.size() == MAX_LEGS) {
            return;
        }
        var last = itinerary.getLast();
        for (var next : departuresByAirport.getOrDefault(last.destination(), List.of())) {
            if (ConnectionRules.connects(last, next)) {
                extend(append(itinerary, next), visitor);
            }
        }
    }

    private static List<Leg> append(List<Leg> itinerary, Leg next) {
        var longer = new ArrayList<>(itinerary);
        longer.add(next);
        return List.copyOf(longer);
    }
}
