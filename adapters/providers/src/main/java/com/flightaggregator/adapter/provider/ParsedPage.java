package com.flightaggregator.adapter.provider;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.domain.Leg;
import java.util.List;
import java.util.Optional;

/**
 * One parsed page of a provider: its valid legs and discarded records in page order, and the
 * position of the next page, empty on the last one. A provider that announces how many pages it has
 * (Beta) gives the count in {@code announcedPages}, empty for one that does not.
 */
record ParsedPage(
    List<Leg> legs,
    List<DiscardedRecord> discarded,
    Optional<String> next,
    Optional<Integer> announcedPages) {

  public ParsedPage {
    legs = List.copyOf(legs);
    discarded = List.copyOf(discarded);
    requireNonNull(next, "next");
    requireNonNull(announcedPages, "announcedPages");
  }

  /** A page of a provider that does not announce its page count. */
  ParsedPage(List<Leg> legs, List<DiscardedRecord> discarded, Optional<String> next) {
    this(legs, discarded, next, Optional.empty());
  }
}
