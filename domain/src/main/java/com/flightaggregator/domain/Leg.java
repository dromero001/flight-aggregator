package com.flightaggregator.domain;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.OffsetDateTime;

/** One provider's offer of one flight. */
public record Leg(
    Carrier carrier,
    int flightNumber,
    Airport origin,
    Airport destination,
    OffsetDateTime departure,
    OffsetDateTime arrival,
    Money price,
    Provider provider,
    Instant quotedAt) {

  /**
   * A leg priced from here up is a provider error. It keeps every itinerary price, three legs with
   * the markup, under the {@link Money} bound of one billion euros.
   */
  static final Money PRICE_CEILING = Money.euros("100000000.00");

  public Leg {
    requireNonNull(carrier, "carrier");
    requireNonNull(origin, "origin");
    requireNonNull(destination, "destination");
    requireNonNull(departure, "departure");
    requireNonNull(arrival, "arrival");
    requireNonNull(price, "price");
    requireNonNull(provider, "provider");
    requireNonNull(quotedAt, "quotedAt");
    if (flightNumber < 1 || flightNumber > 9999) {
      throw new IllegalArgumentException("Flight number must be 1 to 9999: " + flightNumber);
    }
    if (price.compareTo(Money.ZERO) == 0) {
      throw new IllegalArgumentException("A leg cannot be free: " + price);
    }
    if (price.compareTo(PRICE_CEILING) >= 0) {
      throw new IllegalArgumentException("A leg must cost under " + PRICE_CEILING + ": " + price);
    }
    if (origin.equals(destination)) {
      throw new IllegalArgumentException("A leg cannot start and end at " + origin.code());
    }
    if (!arrival.toInstant().isAfter(departure.toInstant())) {
      throw new IllegalArgumentException(
          "Arrival must be after the departure: " + departure + " -> " + arrival);
    }
  }

  public LegKey key() {
    return new LegKey(carrier, flightNumber, origin, destination, departure.toInstant());
  }
}
