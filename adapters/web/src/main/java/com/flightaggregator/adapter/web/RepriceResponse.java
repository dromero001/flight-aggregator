package com.flightaggregator.adapter.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flightaggregator.adapter.web.ItineraryResponse.Price;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a re-price answers with a 200 (Q4-A): its outcome and the itinerary as a search writes it,
 * re-quoted live, always; the price it was shown at only with {@code PRICE_CHANGED}, and §6.5's
 * notice only with {@code ALTERNATIVE}. One body whose {@code outcome} tells the dashboard which
 * state to render (§6.8). The two fields of one outcome each are left out, not written as null:
 * springdoc cannot write a nullable reference to {@code Price} that OpenAPI 3.1 reads as one.
 */
record RepriceResponse(
    @Schema(requiredMode = REQUIRED) Outcome outcome,
    @Schema(requiredMode = REQUIRED) ItineraryResponse itinerary,
    @Schema(description = "The price shown before; only with PRICE_CHANGED")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Price previousPrice,
    @Schema(description = "Why another itinerary is offered; only with ALTERNATIVE")
        @JsonInclude(JsonInclude.Include.NON_NULL)
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
