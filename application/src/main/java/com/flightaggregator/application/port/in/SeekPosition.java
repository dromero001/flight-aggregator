package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Money;
import java.time.Duration;
import java.time.Instant;

/**
 * Where a keyset page ends: the last itinerary's value of each sort key, and its id, the
 * tie-breaker that makes the position unique (§5.4, R11). The next page starts after it in the sort
 * order.
 */
public record SeekPosition(Money price, Instant departure, Duration duration, ItineraryId id) {

  /** The position of this itinerary, the last of a page. */
  public static SeekPosition of(Itinerary itinerary) {
    return new SeekPosition(
        itinerary.price(), itinerary.departure().toInstant(), itinerary.duration(), itinerary.id());
  }
}
