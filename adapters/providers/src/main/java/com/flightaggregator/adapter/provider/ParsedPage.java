package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.domain.Leg;
import java.util.List;
import java.util.Optional;

/**
 * One parsed page of a provider: its valid legs and discarded records in page order, and the
 * position of the next page, empty on the last one.
 */
public record ParsedPage(List<Leg> legs, List<DiscardedRecord> discarded, Optional<String> next) {

  public ParsedPage {
    legs = List.copyOf(legs);
    discarded = List.copyOf(discarded);
  }
}
