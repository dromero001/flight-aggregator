package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.leg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegTest {

  private static final String DEPARTURE = "2026-11-20T08:15+01:00";
  private static final String ARRIVAL = "2026-11-20T09:30+00:00";

  @Test
  void keyIdentifiesTheFlightAcrossProviders() {
    Leg alpha = leg("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL);
    Leg beta = Legs.requoted(alpha, "80.00", Provider.BETA, Legs.QUOTED_AT.plusSeconds(60));

    assertThat(beta.key()).isEqualTo(alpha.key());
  }

  @Test
  void keyUsesTheDepartureInstant() {
    Leg local = leg("IB3170", "MAD-LHR", "2026-11-20T08:15+01:00", ARRIVAL);
    Leg utc = leg("IB3170", "MAD-LHR", "2026-11-20T07:15:00Z", ARRIVAL);

    assertThat(utc.key()).isEqualTo(local.key());
  }

  @Test
  void keyPrintsCanonically() {
    Leg leg = leg("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL);

    assertThat(leg.key().toString()).isEqualTo("IB3170 MAD-LHR 2026-11-20T07:15:00Z");
  }

  @Test
  void rejectsAnArrivalNotAfterTheDeparture() {
    assertThatThrownBy(() -> leg("IB3170", "MAD-LHR", DEPARTURE, DEPARTURE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> leg("IB3170", "MAD-LHR", DEPARTURE, "2026-11-20T07:00:00Z"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAnArrivalWithAnEarlierLocalTime() {
    Leg leg = leg("IB3170", "MAD-LHR", "2026-11-20T08:15+01:00", "2026-11-20T08:00+00:00");

    assertThat(leg.arrival().toInstant()).isAfter(leg.departure().toInstant());
  }

  @Test
  void rejectsTheSameOriginAndDestination() {
    assertThatThrownBy(() -> leg("IB3170", "MAD-MAD", DEPARTURE, ARRIVAL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAFreePrice() {
    Leg leg = leg("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL);

    assertThatThrownBy(() -> Legs.requoted(leg, "0.00", Provider.ALPHA, Legs.QUOTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 9999})
  void acceptsFlightNumbersFromOneToFourDigits(int number) {
    assertThat(leg("IB" + number, "MAD-LHR", DEPARTURE, ARRIVAL).flightNumber()).isEqualTo(number);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 10000})
  void rejectsFlightNumbersOutsideOneToFourDigits(int number) {
    assertThatThrownBy(() -> leg("IB" + number, "MAD-LHR", DEPARTURE, ARRIVAL))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 3, 4, 5, 6, 7, 8})
  void requiresEveryObjectComponent(int missing) {
    Leg leg = leg("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL);
    Object[] components = {
      leg.carrier(),
      leg.flightNumber(),
      leg.origin(),
      leg.destination(),
      leg.departure(),
      leg.arrival(),
      leg.price(),
      leg.provider(),
      leg.quotedAt()
    };
    components[missing] = null;

    assertThatThrownBy(
            () ->
                new Leg(
                    (Carrier) components[0],
                    (int) components[1],
                    (Airport) components[2],
                    (Airport) components[3],
                    (OffsetDateTime) components[4],
                    (OffsetDateTime) components[5],
                    (Money) components[6],
                    (Provider) components[7],
                    (Instant) components[8]))
        .isInstanceOf(NullPointerException.class);
  }
}
