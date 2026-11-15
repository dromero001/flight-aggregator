package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Itinerary;
import java.util.List;

/** One page of stored itineraries, and the generation they belong to. */
public record StoredPage(long generation, List<Itinerary> itineraries) {

  public StoredPage {
    itineraries = List.copyOf(itineraries);
  }
}
