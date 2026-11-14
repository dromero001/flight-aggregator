package com.flightaggregator.mockgds.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A flight leg as one provider sells it.
 *
 * @param airline two-character IATA airline designator
 * @param flightNumber numeric part of the flight number
 * @param origin IATA code of the departure airport
 * @param destination IATA code of the arrival airport
 * @param departure local departure time with the origin's offset
 * @param arrival local arrival time with the destination's offset
 * @param priceEur price in euros, two decimals
 * @param provider the provider selling it
 */
public record Leg(
    String airline,
    int flightNumber,
    String origin,
    String destination,
    OffsetDateTime departure,
    OffsetDateTime arrival,
    BigDecimal priceEur,
    Provider provider) {

  public Leg {
    Objects.requireNonNull(airline);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(departure);
    Objects.requireNonNull(arrival);
    Objects.requireNonNull(priceEur);
    Objects.requireNonNull(provider);
    if (!arrival.isAfter(departure)) {
      throw new IllegalArgumentException(
          "Arrival must be after departure: " + departure + " / " + arrival);
    }
    if (priceEur.signum() <= 0 || priceEur.scale() != 2) {
      throw new IllegalArgumentException("Price must be positive with two decimals: " + priceEur);
    }
  }

  public LegKey key() {
    return new LegKey(airline, flightNumber, origin, destination, departure.toInstant());
  }
}
