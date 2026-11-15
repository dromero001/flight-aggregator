package com.flightaggregator.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A trip of one to three connecting legs. */
public record Itinerary(List<Leg> legs) {

  static final int MAXIMUM_LEGS = 3;

  public Itinerary {
    legs = List.copyOf(legs);
    if (legs.isEmpty() || legs.size() > MAXIMUM_LEGS) {
      throw new IllegalArgumentException("An itinerary has 1 to 3 legs: " + legs.size());
    }
    for (int i = 1; i < legs.size(); i++) {
      if (!canFollow(legs.subList(0, i), legs.get(i))) {
        throw new IllegalArgumentException(
            "Leg " + legs.get(i).key() + " cannot follow " + legs.get(i - 1).key());
      }
    }
  }

  /**
   * Whether the next leg connects to the last of these legs and lands at an airport they have not
   * visited, their origin included.
   */
  static boolean canFollow(List<Leg> legs, Leg next) {
    Airport landing = next.destination();
    return ConnectionRules.connects(legs.getLast(), next)
        && !legs.getFirst().origin().equals(landing)
        && legs.stream().noneMatch(leg -> leg.destination().equals(landing));
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

  /** Whether it can still be sold: its first leg departs after {@code now} (R5). */
  public boolean isSellable(Instant now) {
    return ConnectionRules.isSellable(legs.getFirst(), now);
  }

  public ItineraryId id() {
    return ItineraryId.of(legs.stream().map(Leg::key).toList());
  }
}
