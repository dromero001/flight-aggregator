package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.Itinerary;
import java.util.List;
import java.util.Optional;

/**
 * One page of a search: its itineraries, the cursor of the next page when more rows exist, and
 * whether the inventory changed since the cursor it followed was issued (§6.3, Q1).
 */
public record SearchPage(
    List<Itinerary> itineraries, Optional<PageCursor> next, boolean inventoryChanged) {

  public SearchPage {
    itineraries = List.copyOf(itineraries);
  }
}
