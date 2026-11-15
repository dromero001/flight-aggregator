package com.flightaggregator.application.service;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Test fixture: legs from short strings. */
final class Legs {

  static final Instant QUOTED_AT = Instant.parse("2026-11-20T08:00:00Z");

  private Legs() {}

  /**
   * An offer at 100.00 euros of the flight {@code IB<number>} from {@code origin} to {@code
   * destination}.
   */
  static Leg leg(Provider provider, int number, String origin, String destination) {
    return leg(provider, number, origin, destination, "2026-11-25T07:15:00Z", QUOTED_AT);
  }

  /** The same offer departing at {@code departure} (UTC), flying for two hours. */
  static Leg leg(
      Provider provider,
      int number,
      String origin,
      String destination,
      String departure,
      Instant quotedAt) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Leg(
        new Carrier("IB"),
        number,
        new Airport(origin),
        new Airport(destination),
        departsAt,
        departsAt.plusHours(2),
        Money.euros("100.00"),
        provider,
        quotedAt);
  }

  /** The same offer landing {@code hours} after it departs. */
  static Leg flyingFor(Leg leg, int hours) {
    return new Leg(
        leg.carrier(),
        leg.flightNumber(),
        leg.origin(),
        leg.destination(),
        leg.departure(),
        leg.departure().plusHours(hours),
        leg.price(),
        leg.provider(),
        leg.quotedAt());
  }

  /** The same offer at another price, in euros. */
  static Leg pricedAt(Leg leg, String euros) {
    return new Leg(
        leg.carrier(),
        leg.flightNumber(),
        leg.origin(),
        leg.destination(),
        leg.departure(),
        leg.arrival(),
        Money.euros(euros),
        leg.provider(),
        leg.quotedAt());
  }
}
