package com.flightaggregator.adapter.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One result card (§6.8): the trip, its legs and the final price, with every time at its airport's
 * own offset (§4.2). It carries nothing the card does not show: no provider, no leg price. Every
 * field is always written, so the contract marks each one required.
 */
record ItineraryResponse(
    @Schema(requiredMode = REQUIRED) UUID id,
    @Schema(requiredMode = REQUIRED) String origin,
    @Schema(requiredMode = REQUIRED) String destination,
    @Schema(requiredMode = REQUIRED) OffsetDateTime departure,
    @Schema(requiredMode = REQUIRED) OffsetDateTime arrival,
    @Schema(requiredMode = REQUIRED) long durationMinutes,
    @Schema(requiredMode = REQUIRED) int stops,
    @Schema(requiredMode = REQUIRED) Price price,
    @Schema(requiredMode = REQUIRED) Instant quotedAt,
    @Schema(requiredMode = REQUIRED) List<Leg> legs) {

  static ItineraryResponse of(Itinerary itinerary) {
    return new ItineraryResponse(
        itinerary.id().value(),
        itinerary.origin().code(),
        itinerary.destination().code(),
        itinerary.departure(),
        itinerary.arrival(),
        itinerary.duration().toMinutes(),
        itinerary.legs().size() - 1,
        Price.of(itinerary.price()),
        itinerary.quotedAt(),
        itinerary.legs().stream().map(Leg::of).toList());
  }

  /**
   * An amount in euros, as a decimal string with its cents, so no client rounds it as a float. The
   * contract states euros as the one currency.
   */
  record Price(
      @Schema(requiredMode = REQUIRED) String amount,
      @Schema(requiredMode = REQUIRED, allowableValues = EURO) String currency) {

    static final String EURO = "EUR";

    static Price of(Money money) {
      return new Price(money.amount().toPlainString(), EURO);
    }
  }

  /** One flight of the trip, as the card lists it. */
  record Leg(
      @Schema(requiredMode = REQUIRED) String carrier,
      @Schema(requiredMode = REQUIRED) int flightNumber,
      @Schema(requiredMode = REQUIRED) String origin,
      @Schema(requiredMode = REQUIRED) String destination,
      @Schema(requiredMode = REQUIRED) OffsetDateTime departure,
      @Schema(requiredMode = REQUIRED) OffsetDateTime arrival) {

    static Leg of(com.flightaggregator.domain.Leg leg) {
      return new Leg(
          leg.carrier().code(),
          leg.flightNumber(),
          leg.origin().code(),
          leg.destination().code(),
          leg.departure(),
          leg.arrival());
    }
  }
}
