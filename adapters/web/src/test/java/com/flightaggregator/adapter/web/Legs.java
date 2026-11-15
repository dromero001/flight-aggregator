package com.flightaggregator.adapter.web;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Test fixture: a leg from its fields as text. */
final class Legs {

  private Legs() {}

  static Leg leg(
      String carrier,
      int number,
      String origin,
      String destination,
      String departure,
      String arrival,
      String price,
      Provider provider,
      String quotedAt) {
    return new Leg(
        new Carrier(carrier),
        number,
        new Airport(origin),
        new Airport(destination),
        OffsetDateTime.parse(departure),
        OffsetDateTime.parse(arrival),
        Money.euros(price),
        provider,
        Instant.parse(quotedAt));
  }
}
