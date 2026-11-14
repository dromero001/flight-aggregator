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
            .filter(next -> !visits(legs, next.destination()))
            .flatMap(next -> extend(append(legs, next)));
    return Stream.concat(Stream.of(itinerary), longer);
  }

  /**
   * The legs leaving the inbound leg's arrival airport inside the connection window of {@link
   * ConnectionRules}, both ends inclusive.
   */
  private Stream<Leg> connectionsAfter(Leg inbound) {
    Instant arrival = inbound.arrival().toInstant();
    return departures
        .getOrDefault(inbound.destination(), Collections.emptyNavigableMap())
        .subMap(
            arrival.plus(ConnectionRules.MINIMUM_CONNECTION_TIME),
            true,
            arrival.plus(ConnectionRules.MAXIMUM_LAYOVER),
            true)
        .values()
        .stream()
        .flatMap(List::stream);
  }

  /** Whether these legs already pass through the airport, at their origin or at a stop. */
  private static boolean visits(List<Leg> legs, Airport airport) {
    return legs.getFirst().origin().equals(airport)
        || legs.stream().anyMatch(leg -> leg.destination().equals(airport));
  }

  private static List<Leg> append(List<Leg> legs, Leg next) {
    return Stream.concat(legs.stream(), Stream.of(next)).toList();
  }
}
