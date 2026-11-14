package com.flightaggregator.mockgds.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ItineraryEnumeratorTest {

  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

  @Test
  void acceptsConnectionsBetweenFortyFiveMinutesAndTwentyFourHoursAtTheSameAirport() {
    var svqMad =
        leg("IB", 1, "SVQ", "MAD", "2026-11-20T08:00:00+01:00", "2026-11-20T09:00:00+01:00");
    var madLhrExactMct =
        leg("IB", 2, "MAD", "LHR", "2026-11-20T09:45:00+01:00", "2026-11-20T11:00:00Z");
    var madCdgTooShort =
        leg("AF", 3, "MAD", "CDG", "2026-11-20T09:30:00+01:00", "2026-11-20T11:30:00+01:00");
    var madJfkTooLate =
        leg("IB", 4, "MAD", "JFK", "2026-11-21T09:30:00+01:00", "2026-11-21T12:00:00-05:00");
    var bcnFcoOtherAirport =
        leg("VY", 5, "BCN", "FCO", "2026-11-20T11:00:00+01:00", "2026-11-20T13:00:00+01:00");

    var itineraries =
        enumerate(
            List.of(svqMad, madLhrExactMct, madCdgTooShort, madJfkTooLate, bcnFcoOtherAirport));

    assertThat(itineraries).contains(List.of(svqMad, madLhrExactMct));
    assertThat(itineraries)
        .filteredOn(itinerary -> itinerary.size() > 1)
        .containsExactly(List.of(svqMad, madLhrExactMct));
  }

  @Test
  void comparesInstantsNotWallClockTimesAcrossOffsets() {
    var lhrMad = leg("BA", 1, "LHR", "MAD", "2026-11-20T08:00:00Z", "2026-11-20T10:20:00Z");
    var madLisTenMinutesLater =
        leg("TP", 2, "MAD", "LIS", "2026-11-20T11:30:00+01:00", "2026-11-20T11:50:00Z");
    var madLisFiftyMinutesLater =
        leg("TP", 3, "MAD", "LIS", "2026-11-20T12:10:00+01:00", "2026-11-20T12:30:00Z");

    var itineraries = enumerate(List.of(lhrMad, madLisTenMinutesLater, madLisFiftyMinutesLater));

    assertThat(itineraries)
        .contains(List.of(lhrMad, madLisFiftyMinutesLater))
        .doesNotContain(List.of(lhrMad, madLisTenMinutesLater));
  }

  @Test
  void buildsItinerariesOfUpToThreeLegs() {
    var a = leg("IB", 1, "SVQ", "MAD", "2026-11-20T06:00:00+01:00", "2026-11-20T07:00:00+01:00");
    var b = leg("IB", 2, "MAD", "CDG", "2026-11-20T08:00:00+01:00", "2026-11-20T10:00:00+01:00");
    var c = leg("AF", 3, "CDG", "NCE", "2026-11-20T11:00:00+01:00", "2026-11-20T12:30:00+01:00");
    var d = leg("U2", 4, "NCE", "GVA", "2026-11-20T14:00:00+01:00", "2026-11-20T15:00:00+01:00");

    var itineraries = enumerate(List.of(a, b, c, d));

    assertThat(itineraries).contains(List.of(a, b, c)).doesNotContain(List.of(a, b, c, d));
    assertThat(
            itineraries.stream().collect(Collectors.groupingBy(List::size, Collectors.counting())))
        .containsEntry(1, 4L)
        .containsEntry(2, 3L)
        .containsEntry(3, 2L);
  }

  @Test
  void rejectsItinerariesThatVisitAnAirportTwice() {
    var madLhr = leg("IB", 1, "MAD", "LHR", "2026-11-20T08:00:00+01:00", "2026-11-20T09:20:00Z");
    var lhrMad = leg("BA", 2, "LHR", "MAD", "2026-11-20T10:30:00Z", "2026-11-20T13:50:00+01:00");
    var madBcn =
        leg("IB", 3, "MAD", "BCN", "2026-11-20T15:00:00+01:00", "2026-11-20T16:15:00+01:00");

    var itineraries = enumerate(List.of(madLhr, lhrMad, madBcn));

    assertThat(itineraries)
        .containsExactlyInAnyOrder(
            List.of(madLhr), List.of(lhrMad), List.of(madBcn), List.of(lhrMad, madBcn));
  }

  @Test
  void connectedSequencesStillIncludeTheOnesThatVisitAnAirportTwice() {
    var madLhr = leg("IB", 1, "MAD", "LHR", "2026-11-20T08:00:00+01:00", "2026-11-20T09:20:00Z");
    var lhrMad = leg("BA", 2, "LHR", "MAD", "2026-11-20T10:30:00Z", "2026-11-20T13:50:00+01:00");
    var madBcn =
        leg("IB", 3, "MAD", "BCN", "2026-11-20T15:00:00+01:00", "2026-11-20T16:15:00+01:00");
    var sequences = new ArrayList<List<Leg>>();

    ItineraryEnumerator.of(List.of(madLhr, lhrMad, madBcn), NOW)
        .forEachConnectedSequence(sequences::add);

    assertThat(sequences)
        .containsExactlyInAnyOrder(
            List.of(madLhr),
            List.of(lhrMad),
            List.of(madBcn),
            List.of(lhrMad, madBcn),
            List.of(madLhr, lhrMad),
            List.of(madLhr, lhrMad, madBcn));
  }

  @Test
  void keepsTheCheapestOfferOfALegAndDropsDepartedLegs() {
    var alpha =
        leg(
            "IB",
            1,
            "MAD",
            "LHR",
            "2026-11-20T08:00:00+01:00",
            "2026-11-20T09:20:00Z",
            "120.00",
            Provider.ALPHA);
    var beta =
        leg(
            "IB",
            1,
            "MAD",
            "LHR",
            "2026-11-20T08:00:00+01:00",
            "2026-11-20T09:20:00Z",
            "99.00",
            Provider.BETA);
    var departed =
        leg("IB", 2, "MAD", "BCN", "2026-11-19T22:00:00+01:00", "2026-11-19T23:15:00+01:00");

    assertThat(enumerate(List.of(alpha, beta, departed))).containsExactly(List.of(beta));
  }

  private static List<List<Leg>> enumerate(List<Leg> legs) {
    var itineraries = new ArrayList<List<Leg>>();
    ItineraryEnumerator.of(legs, NOW).forEachItinerary(itineraries::add);
    return itineraries;
  }

  private static Leg leg(
      String airline,
      int number,
      String origin,
      String destination,
      String departure,
      String arrival) {
    return leg(airline, number, origin, destination, departure, arrival, "100.00", Provider.ALPHA);
  }

  private static Leg leg(
      String airline,
      int number,
      String origin,
      String destination,
      String departure,
      String arrival,
      String price,
      Provider provider) {
    return new Leg(
        airline,
        number,
        origin,
        destination,
        OffsetDateTime.parse(departure),
        OffsetDateTime.parse(arrival),
        new BigDecimal(price),
        provider);
  }
}
