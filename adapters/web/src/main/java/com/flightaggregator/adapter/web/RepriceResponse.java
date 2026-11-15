package com.flightaggregator.adapter.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flightaggregator.adapter.web.ItineraryResponse.Price;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a re-price answers with a 200 (Q4-A): its outcome, the itinerary as a search writes it,
 * re-quoted live, and, by outcome, the price it was shown at or §6.5's notice. One body whose
 * {@code outcome} tells the dashboard which state to render (§6.8). Every field is always written,
 * the two that only one outcome has as null otherwise.
 */
record RepriceResponse(
    @Schema(requiredMode = REQUIRED) Outcome outcome,
    @Schema(requiredMode = REQUIRED) ItineraryResponse itinerary,
    @Schema(requiredMode = REQUIRED, description = "The price shown before; only PRICE_CHANGED")
        Price previousPrice,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"},
            description = "Why another itinerary is offered; only ALTERNATIVE")
        String notice) {

  /** §6.5's text, word for word. */
  static final String ALTERNATIVE_NOTICE =
      "We couldn't confirm this flight. This is the best alternative we found.";

  enum Outcome {
    /** The choice, at the price it was shown at. */
    CONFIRMED,
    /** The choice, at another price than the one it was shown at. */
    PRICE_CHANGED,
    /** The choice could not be confirmed; this itinerary of the same route was. */
    ALTERNATIVE
  }

  static RepriceResponse confirmed(Itinerary itinerary) {
    return new RepriceResponse(Outcome.CONFIRMED, ItineraryResponse.of(itinerary), null, null);
  }

  static RepriceResponse priceChanged(Itinerary itinerary, Money previousPrice) {
    return new RepriceResponse(
        Outcome.PRICE_CHANGED, ItineraryResponse.of(itinerary), Price.of(previousPrice), null);
  }

  static RepriceResponse alternative(Itinerary itinerary) {
    return new RepriceResponse(
        Outcome.ALTERNATIVE, ItineraryResponse.of(itinerary), null, ALTERNATIVE_NOTICE);
  }
}
