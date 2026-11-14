package com.flightaggregator.domain;

import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toCollection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merges the offers of one flight from several providers into the best one. */
public final class LegDeduplicator {

  private static final Comparator<Leg> BEST_FIRST =
      Comparator.comparing(Leg::price)
          .thenComparing(Leg::quotedAt, Comparator.reverseOrder())
          .thenComparing(Leg::provider);

  private LegDeduplicator() {}

  /**
   * One leg per flight: the cheapest offer, then the latest quote, then the provider declared
   * first. A full tie is then the same offer twice: the same provider, price, quote time and
   * arrival, so which one is kept makes no difference.
   *
   * <p>A provider that gives one flight two different arrivals contradicts itself: all its offers
   * of that flight are returned as {@link Deduplication#conflicting()} and take no part in the
   * choice. Other providers' offers of the flight are chosen among as usual.
   */
  public static Deduplication deduplicate(Collection<Leg> offers) {
    Map<LegKey, List<Leg>> byFlight = new LinkedHashMap<>();
    for (Leg offer : offers) {
      byFlight.computeIfAbsent(offer.key(), key -> new ArrayList<>()).add(offer);
    }
    List<Leg> legs = new ArrayList<>();
    List<Leg> conflicting = new ArrayList<>();
    for (List<Leg> flightOffers : byFlight.values()) {
      Set<Provider> contradicting = contradictingProviders(flightOffers);
      Map<Boolean, List<Leg>> split =
          flightOffers.stream().collect(partitioningBy(o -> contradicting.contains(o.provider())));
      conflicting.addAll(split.get(true));
      split.get(false).stream()
          .reduce((kept, other) -> BEST_FIRST.compare(other, kept) < 0 ? other : kept)
          .ifPresent(legs::add);
    }
    return new Deduplication(legs, conflicting);
  }

  private static Set<Provider> contradictingProviders(List<Leg> flightOffers) {
    Map<Provider, Set<Instant>> arrivals = new EnumMap<>(Provider.class);
    for (Leg offer : flightOffers) {
      arrivals
          .computeIfAbsent(offer.provider(), provider -> new HashSet<>())
          .add(offer.arrival().toInstant());
    }
    return arrivals.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .collect(toCollection(() -> EnumSet.noneOf(Provider.class)));
  }
}
