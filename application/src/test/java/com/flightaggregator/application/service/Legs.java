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
    return new Leg(
        new Carrier("IB"),
        number,
        new Airport(origin),
        new Airport(destination),
        OffsetDateTime.parse("2026-11-25T07:15:00Z"),
        OffsetDateTime.parse("2026-11-25T09:15:00Z"),
        Money.euros("100.00"),
        provider,
        QUOTED_AT);
  }
}
