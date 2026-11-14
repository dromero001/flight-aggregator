package com.flightaggregator.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A trip of one to three connecting legs. */
public record Itinerary(List<Leg> legs) {

  private static final int MAXIMUM_LEGS = 3;

  public Itinerary {
    legs = List.copyOf(legs);
    if (legs.isEmpty() || legs.size() > MAXIMUM_LEGS) {
      throw new IllegalArgumentException("An itinerary has 1 to 3 legs: " + legs.size());
    }
    for (int i = 1; i < legs.size(); i++) {
      if (!ConnectionRules.connects(legs.get(i - 1), legs.get(i))) {
        throw new IllegalArgumentException(
            "Legs do not connect: " + legs.get(i - 1).key() + " -> " + legs.get(i).key());
      }
    }
    Set<Airport> visited = new HashSet<>();
    visited.add(legs.getFirst().origin());
    for (Leg leg : legs) {
      if (!visited.add(leg.destination())) {
        throw new IllegalArgumentException("Airport visited twice: " + leg.destination().code());
      }
    }
  }

  public Airport origin() {
    return legs.getFirst().origin();
  }

  public Airport destination() {
    return legs.getLast().destination();
  }

  public OffsetDateTime departure() {
    return legs.getFirst().departure();
  }

  public OffsetDateTime arrival() {
    return legs.getLast().arrival();
  }

  public Duration duration() {
    return Duration.between(departure().toInstant(), arrival().toInstant());
  }

  public Money price() {
    return PricingPolicy.finalPrice(legs);
  }

  /** The oldest quote among the legs: the itinerary is only as fresh as its stalest leg. */
  public Instant quotedAt() {
    return legs.stream().map(Leg::quotedAt).min(Comparator.naturalOrder()).orElseThrow();
  }

  /** The carrier, when it operates every leg; empty when the trip interlines. */
  public Optional<Carrier> singleCarrier() {
    Carrier first = legs.getFirst().carrier();
    return legs.stream().allMatch(leg -> leg.carrier().equals(first))
        ? Optional.of(first)
        : Optional.empty();
  }

  public ItineraryId id() {
    return ItineraryId.of(legs.stream().map(Leg::key).toList());
  }
}
