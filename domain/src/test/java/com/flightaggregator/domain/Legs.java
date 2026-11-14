package com.flightaggregator.domain;

import java.time.Instant;
import java.time.OffsetDateTime;

/** Test fixture: builds legs from short strings. */
final class Legs {

  static final Instant QUOTED_AT = Instant.parse("2026-11-19T11:00:00Z");

  private Legs() {}

  /** A leg at 100.00 from ALPHA, quoted at {@link #QUOTED_AT}. */
  static Leg leg(String flight, String route, String departure, String arrival) {
    return offer(flight, route, departure, arrival, "100.00", Provider.ALPHA);
  }

  static Leg offer(
      String flight,
      String route,
      String departure,
      String arrival,
      String price,
      Provider provider) {
    String[] airports = route.split("-");
    return new Leg(
        new Carrier(flight.substring(0, 2)),
        Integer.parseInt(flight.substring(2)),
        new Airport(airports[0]),
        new Airport(airports[1]),
        OffsetDateTime.parse(departure),
        OffsetDateTime.parse(arrival),
        Money.euros(price),
        provider,
        QUOTED_AT);
  }

  /** The same flight, as a new offer. */
  static Leg requoted(Leg leg, String price, Provider provider, Instant quotedAt) {
    return new Leg(
        leg.carrier(),
        leg.flightNumber(),
        leg.origin(),
        leg.destination(),
        leg.departure(),
        leg.arrival(),
        Money.euros(price),
        provider,
        quotedAt);
  }
}
