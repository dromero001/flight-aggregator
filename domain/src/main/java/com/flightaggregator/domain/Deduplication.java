package com.flightaggregator.domain;

import java.util.List;

/**
 * The outcome of deduplicating offers: one offer per flight, and every offer discarded because its
 * provider gave that flight two different arrivals.
 */
public record Deduplication(List<Leg> legs, List<Leg> conflicting) {

  public Deduplication {
    legs = List.copyOf(legs);
    conflicting = List.copyOf(conflicting);
  }
}
