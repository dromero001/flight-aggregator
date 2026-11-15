package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.leg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItineraryTest {

  private static final Leg MAD_LHR =
      leg("IB3170", "MAD-LHR", "2026-11-20T08:15+01:00", "2026-11-20T09:30+00:00");
  private static final Leg LHR_JFK =
      leg("BA117", "LHR-JFK", "2026-11-20T11:10+00:00", "2026-11-20T14:20-05:00");
  private static final Leg JFK_BOG =
      leg("AV245", "JFK-BOG", "2026-11-20T21:00-05:00", "2026-11-21T02:30-05:00");
  private static final Leg BOG_LIM =
      leg("AV9", "BOG-LIM", "2026-11-21T04:00-05:00", "2026-11-21T07:00-05:00");

  @Test
  void rejectsNoLegsAndMoreThanThree() {
    assertThatThrownBy(() -> new Itinerary(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Itinerary(List.of(MAD_LHR, LHR_JFK, JFK_BOG, BOG_LIM)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsLegsThatDoNotConnect() {
    Leg lgwJfk = leg("BA175", "LGW-JFK", "2026-11-20T11:10+00:00", "2026-11-20T14:20-05:00");
    Leg tooSoon = leg("BA117", "LHR-JFK", "2026-11-20T10:00+00:00", "2026-11-20T13:00-05:00");

    assertThatThrownBy(() -> new Itinerary(List.of(MAD_LHR, lgwJfk)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Itinerary(List.of(MAD_LHR, tooSoon)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsVisitingAnAirportTwice() {
    Leg lhrMad = leg("IB3171", "LHR-MAD", "2026-11-20T11:10+00:00", "2026-11-20T14:20+01:00");
    Leg lhrCdg = leg("AF1081", "LHR-CDG", "2026-11-20T11:10+00:00", "2026-11-20T13:20+01:00");
    Leg cdgLhr = leg("AF1180", "CDG-LHR", "2026-11-20T15:00+01:00", "2026-11-20T15:30+00:00");

    assertThatThrownBy(() -> new Itinerary(List.of(MAD_LHR, lhrMad)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Itinerary(List.of(MAD_LHR, lhrCdg, cdgLhr)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void describesItsTrip() {
    Itinerary itinerary = new Itinerary(List.of(MAD_LHR, LHR_JFK));

    assertThat(itinerary.origin()).isEqualTo(new Airport("MAD"));
    assertThat(itinerary.destination()).isEqualTo(new Airport("JFK"));
    assertThat(itinerary.departure()).isEqualTo(OffsetDateTime.parse("2026-11-20T08:15+01:00"));
    assertThat(itinerary.departure().getOffset().getTotalSeconds()).isEqualTo(3600);
    assertThat(itinerary.arrival()).isEqualTo(OffsetDateTime.parse("2026-11-20T14:20-05:00"));
    assertThat(itinerary.arrival().getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
    assertThat(itinerary.duration()).isEqualTo(Duration.ofHours(12).plusMinutes(5));
  }

  @Test
  void isPricedByThePricingPolicy() {
    List<Leg> legs = List.of(MAD_LHR, LHR_JFK);

    assertThat(new Itinerary(legs).price()).isEqualTo(PricingPolicy.finalPrice(legs));
  }

  @Test
  void isAsFreshAsItsOldestQuote() {
    Leg first =
        Legs.requoted(MAD_LHR, "100.00", Provider.ALPHA, Instant.parse("2026-11-19T12:00:00Z"));
    Leg second =
        Legs.requoted(LHR_JFK, "100.00", Provider.ALPHA, Instant.parse("2026-11-19T11:30:00Z"));

    assertThat(new Itinerary(List.of(first, second)).quotedAt())
        .isEqualTo(Instant.parse("2026-11-19T11:30:00Z"));
  }

  @Test
  void isAsFreshAsItsOldestQuoteWhateverItsPosition() {
    Leg first =
        Legs.requoted(MAD_LHR, "100.00", Provider.ALPHA, Instant.parse("2026-11-19T11:30:00Z"));
    Leg second =
        Legs.requoted(LHR_JFK, "100.00", Provider.ALPHA, Instant.parse("2026-11-19T12:00:00Z"));

    assertThat(new Itinerary(List.of(first, second)).quotedAt())
        .isEqualTo(Instant.parse("2026-11-19T11:30:00Z"));
  }

  @Test
  void belongsToACarrierOnlyWhenItOperatesEveryLeg() {
    Leg ibOnward = leg("IB6251", "LHR-JFK", "2026-11-20T11:10+00:00", "2026-11-20T14:20-05:00");

    assertThat(new Itinerary(List.of(MAD_LHR, ibOnward)).singleCarrier())
        .contains(new Carrier("IB"));
    assertThat(new Itinerary(List.of(MAD_LHR, LHR_JFK)).singleCarrier()).isEmpty();
  }

  @Test
  void keepsItsIdWhenRequoted() {
    Leg first =
        Legs.requoted(MAD_LHR, "80.00", Provider.BETA, Instant.parse("2026-11-19T12:00:00Z"));
    Leg second =
        Legs.requoted(LHR_JFK, "90.00", Provider.GAMMA, Instant.parse("2026-11-19T12:05:00Z"));
    Itinerary original = new Itinerary(List.of(MAD_LHR, LHR_JFK));
    Itinerary requoted = new Itinerary(List.of(first, second));

    assertThat(requoted.id()).isEqualTo(original.id());
    assertThat(requoted.price()).isNotEqualTo(original.price());
  }

  @Test
  void cannotBeChangedAfterCreation() {
    List<Leg> legs = new ArrayList<>(List.of(MAD_LHR, LHR_JFK));
    Itinerary itinerary = new Itinerary(legs);

    legs.add(JFK_BOG);

    assertThat(itinerary.legs()).containsExactly(MAD_LHR, LHR_JFK);
  }

  /** R5: a trip whose first leg has left can no longer be sold, whatever its later legs. */
  @Test
  void isSellableUntilItsFirstLegDeparts() {
    Itinerary itinerary = new Itinerary(List.of(MAD_LHR, LHR_JFK));
    Instant departure = MAD_LHR.departure().toInstant();

    assertThat(itinerary.isSellable(departure.minusSeconds(1))).isTrue();
    assertThat(itinerary.isSellable(departure)).isFalse();
    assertThat(itinerary.isSellable(LHR_JFK.departure().toInstant().minusSeconds(1))).isFalse();
  }

  @Test
  void acceptsThreeConnectingLegs() {
    Itinerary itinerary = new Itinerary(List.of(MAD_LHR, LHR_JFK, JFK_BOG));

    assertThat(itinerary.origin()).isEqualTo(new Airport("MAD"));
    assertThat(itinerary.destination()).isEqualTo(new Airport("BOG"));
    assertThat(itinerary.legs()).hasSize(3);
  }
}
