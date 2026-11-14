package com.flightaggregator.domain;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Builds every sellable itinerary from the legs that providers offer. */
public final class ItineraryBuilder {

  /** The sellable legs by origin airport, each airport's legs ordered by departure instant. */
  private final Map<Airport, NavigableMap<Instant, List<Leg>>> departures;

  private ItineraryBuilder(List<Leg> legs) {
    departures =
        legs.stream()
            .collect(
                groupingBy(
                    Leg::origin,
                    groupingBy(leg -> leg.departure().toInstant(), TreeMap::new, toList())));
  }

  /**
   * Every sellable itinerary of one to three legs, each exactly once, in no particular order. Each
   * flight is taken at its best offer, and legs departing at or before {@code now} are left out.
   * The stream is lazy: it builds the itineraries of one first leg at a time.
   */
  public static Stream<Itinerary> build(Collection<Leg> offers, Instant now) {
    List<Leg> legs =
        LegDeduplicator.deduplicate(offers).stream()
            .filter(leg -> ConnectionRules.isSellable(leg, now))
            .toList();
    ItineraryBuilder builder = new ItineraryBuilder(legs);
    return legs.stream().flatMap(first -> builder.extend(List.of(first)));
  }

  /** These legs as an itinerary, followed by every longer itinerary that starts with them. */
  private Stream<Itinerary> extend(List<Leg> legs) {
    Itinerary itinerary = new Itinerary(legs);
    if (legs.size() == Itinerary.MAXIMUM_LEGS) {
      return Stream.of(itinerary);
    }
    Stream<Itinerary> longer =
        connectionsAfter(legs.getLast())
            .filter(next -> Itinerary.canFollow(legs, next))
            .flatMap(next -> extend(append(legs, next)));
    return Stream.concat(Stream.of(itinerary), longer);
  }

  /**
   * The legs leaving the inbound leg's arrival airport inside its connection window, both ends
   * included: the candidates that {@link Itinerary#canFollow} then decides on.
   */
  private Stream<Leg> connectionsAfter(Leg inbound) {
    return departures
        .getOrDefault(inbound.destination(), Collections.emptyNavigableMap())
        .subMap(
            ConnectionRules.earliestOnwardDeparture(inbound),
            true,
            ConnectionRules.latestOnwardDeparture(inbound),
            true)
        .values()
        .stream()
        .flatMap(List::stream);
  }

  private static List<Leg> append(List<Leg> legs, Leg next) {
    return Stream.concat(legs.stream(), Stream.of(next)).toList();
  }
}
