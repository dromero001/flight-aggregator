package com.flightaggregator.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges the offers of one flight from several providers into the best one. */
public final class LegDeduplicator {

  private static final Comparator<Leg> BEST_FIRST =
      Comparator.comparing(Leg::price)
          .thenComparing(Leg::quotedAt, Comparator.reverseOrder())
          .thenComparing(Leg::provider);

  private LegDeduplicator() {}

  /**
   * One leg per flight: the cheapest offer, then the latest quote, then the provider declared
   * first. Two offers from the same provider at the same price and quote time are a full tie, even
   * when they differ outside the flight's key, for example in the arrival: the first one seen is
   * kept, so the winner depends on the input order. That tie-break is for the user to decide.
   */
  public static List<Leg> deduplicate(Collection<Leg> offers) {
    Map<LegKey, Leg> best = new LinkedHashMap<>();
    for (Leg offer : offers) {
      best.merge(
          offer.key(), offer, (kept, other) -> BEST_FIRST.compare(other, kept) < 0 ? other : kept);
    }
    return List.copyOf(best.values());
  }
}
