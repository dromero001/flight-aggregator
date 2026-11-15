package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Money;

/** What a re-price answers (Q4). */
public sealed interface RepriceResult {

  /** The choice, re-quoted live at the price it was shown at. */
  record Confirmed(Itinerary itinerary) implements RepriceResult {}

  /** The choice, re-quoted live at another price than the one it was shown at. */
  record PriceChanged(Itinerary itinerary, Money previousPrice) implements RepriceResult {}

  /** The choice could not be confirmed; this itinerary of the same route was, live (§6.5). */
  record Alternative(Itinerary itinerary) implements RepriceResult {}

  /** Neither the choice nor any alternative could be confirmed (§6.5). */
  record Unconfirmable() implements RepriceResult {}

  /** No itinerary with that id in the active generation. */
  record NotFound() implements RepriceResult {}
}
