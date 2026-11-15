package com.flightaggregator.adapter.persistence.support;

import static com.flightaggregator.domain.Provider.ALPHA;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Itineraries for the tests that store them. */
public final class Trips {

  private Trips() {}

  /** {@code IB<number>} from Madrid to London, departing at {@code departure} for two hours. */
  public static Itinerary direct(int number, String departure) {
    return direct(number, departure, Duration.ofHours(2));
  }

  public static Itinerary direct(int number, String departure, Duration flying) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Itinerary(
        List.of(
            new Leg(
                new Carrier("IB"),
                number,
                new Airport("MAD"),
                new Airport("LHR"),
                departsAt,
                departsAt.plus(flying),
                Money.euros("100.00"),
                ALPHA,
                Instant.parse("2026-11-15T10:00:00Z"))));
  }
}
