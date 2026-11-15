package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Leg;
import java.util.List;

/**
 * What a provider returned once every page arrived: its legs in page order, and what it dropped.
 */
public record ProviderDownload(List<Leg> legs, List<DiscardedRecord> discarded) {

  public ProviderDownload {
    legs = List.copyOf(legs);
    discarded = List.copyOf(discarded);
  }
}
