package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.QUOTED_AT;
import static com.flightaggregator.domain.Legs.leg;
import static com.flightaggregator.domain.Legs.requoted;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ItineraryBuilderTest {

  private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

  private static final Leg MAD_LHR =
      leg("IB3170", "MAD-LHR", "2026-11-20T08:15+01:00", "2026-11-20T09:30+00:00");
  private static final Leg LHR_JFK =
      leg("BA117", "LHR-JFK", "2026-11-20T11:10+00:00", "2026-11-20T14:20-05:00");
  private static final Leg JFK_BOG =
      leg("AV245", "JFK-BOG", "2026-11-20T21:00-05:00", "2026-11-21T02:30-05:00");

  @Test
  void buildsADirectItineraryForEverySellableLeg() {
    assertThat(ItineraryBuilder.build(LegDeduplicator.deduplicate(List.of(MAD_LHR)), NOW))
        .containsExactly(new Itinerary(List.of(MAD_LHR)));
  }

  @Test
  void buildsOneAndTwoStopItineraries() {
    assertThat(routes(List.of(MAD_LHR, LHR_JFK, JFK_BOG)))
        .containsExactlyInAnyOrder(
            "MAD-LHR", "LHR-JFK", "JFK-BOG", "MAD-LHR-JFK", "LHR-JFK-BOG", "MAD-LHR-JFK-BOG");
  }

  @Test
  void neverBuildsMoreThanTwoStops() {
    Leg bogLim = leg("AV81", "BOG-LIM", "2026-11-21T04:00-05:00", "2026-11-21T07:10-05:00");

    assertThat(routes(List.of(MAD_LHR, LHR_JFK, JFK_BOG, bogLim)))
        .containsExactlyInAnyOrder(
            "MAD-LHR",
            "LHR-JFK",
            "JFK-BOG",
            "BOG-LIM",
            "MAD-LHR-JFK",
            "LHR-JFK-BOG",
            "JFK-BOG-LIM",
            "MAD-LHR-JFK-BOG",
            "LHR-JFK-BOG-LIM");
  }

  @Test
  void usesTheCheapestOfferOfEachLeg() {
    Leg alpha = requoted(MAD_LHR, "120.00", Provider.ALPHA, QUOTED_AT);
    Leg beta = requoted(MAD_LHR, "99.90", Provider.BETA, QUOTED_AT);

    assertThat(ItineraryBuilder.build(LegDeduplicator.deduplicate(List.of(alpha, beta)), NOW))
        .singleElement()
        .satisfies(
            itinerary -> {
              assertThat(itinerary.legs()).containsExactly(beta);
              assertThat(itinerary.price()).isEqualTo(Money.euros("104.90"));
            });
  }

  @Test
  void skipsDepartedLegs() {
    Leg departed = leg("IB3166", "MAD-LHR", "2026-11-19T11:00+01:00", "2026-11-19T12:15+00:00");
    Leg onward = leg("BA117", "LHR-JFK", "2026-11-19T14:00+00:00", "2026-11-19T17:10-05:00");
    Leg departingNow = leg("BA304", "LHR-CDG", "2026-11-19T12:00+00:00", "2026-11-19T14:15+01:00");

    assertThat(routes(List.of(departed, onward, departingNow))).containsExactly("LHR-JFK");
  }

  @Test
  void rejectsShortConnections() {
    Leg tooSoon = leg("BA117", "LHR-JFK", "2026-11-20T10:14+00:00", "2026-11-20T13:20-05:00");

    assertThat(routes(List.of(MAD_LHR, tooSoon))).containsExactlyInAnyOrder("MAD-LHR", "LHR-JFK");
  }

  @Test
  void findsEveryConnectionInsideTheWindow() {
    Leg after30Minutes =
        leg("BA101", "LHR-JFK", "2026-11-20T10:00+00:00", "2026-11-20T13:00-05:00");
    Leg after45Minutes =
        leg("BA103", "LHR-JFK", "2026-11-20T10:15+00:00", "2026-11-20T13:15-05:00");
    Leg after12Hours = leg("BA105", "LHR-JFK", "2026-11-20T21:30+00:00", "2026-11-21T00:30-05:00");
    Leg after24Hours = leg("BA107", "LHR-JFK", "2026-11-21T09:30+00:00", "2026-11-21T12:30-05:00");
    Leg after24HoursAndAMinute =
        leg("BA109", "LHR-JFK", "2026-11-21T09:31+00:00", "2026-11-21T12:31-05:00");
    List<Leg> offers =
        List.of(
            MAD_LHR,
            after30Minutes,
            after45Minutes,
            after12Hours,
            after24Hours,
            after24HoursAndAMinute);

    assertThat(onwardLegs(offers))
        .containsExactlyInAnyOrder(after45Minutes, after12Hours, after24Hours);
  }

  @Test
  void rejectsReturningToTheOrigin() {
    Leg lhrMad = leg("IB3171", "LHR-MAD", "2026-11-20T11:10+00:00", "2026-11-20T14:20+01:00");

    assertThat(routes(List.of(MAD_LHR, lhrMad))).containsExactlyInAnyOrder("MAD-LHR", "LHR-MAD");
  }

  @Test
  void rejectsRevisitingAStop() {
    Leg lhrCdg = leg("AF1081", "LHR-CDG", "2026-11-20T11:00+00:00", "2026-11-20T13:15+01:00");
    Leg cdgLhr = leg("AF1180", "CDG-LHR", "2026-11-20T14:00+01:00", "2026-11-20T14:15+00:00");

    assertThat(routes(List.of(MAD_LHR, lhrCdg, cdgLhr)))
        .containsExactlyInAnyOrder("MAD-LHR", "LHR-CDG", "CDG-LHR", "MAD-LHR-CDG");
  }

  @Test
  void interlinesAcrossCarriersAndProviders() {
    Leg fromGamma = requoted(LHR_JFK, "100.00", Provider.GAMMA, QUOTED_AT);

    assertThat(
            ItineraryBuilder.build(LegDeduplicator.deduplicate(List.of(MAD_LHR, fromGamma)), NOW)
                .filter(itinerary -> itinerary.legs().size() == 2))
        .singleElement()
        .satisfies(
            itinerary -> {
              assertThat(itinerary.legs()).containsExactly(MAD_LHR, fromGamma);
              assertThat(itinerary.singleCarrier()).isEmpty();
            });
  }

  @Test
  void buildsEveryItineraryOnce() {
    List<Leg> offers =
        List.of(
            MAD_LHR,
            leg("IB3174", "MAD-LHR", "2026-11-20T11:15+01:00", "2026-11-20T12:30+00:00"),
            leg("IB3178", "MAD-LHR", "2026-11-20T16:15+01:00", "2026-11-20T17:30+00:00"),
            LHR_JFK,
            leg("BA113", "LHR-JFK", "2026-11-20T14:00+00:00", "2026-11-20T17:10-05:00"),
            leg("BA115", "LHR-JFK", "2026-11-20T18:30+00:00", "2026-11-20T21:40-05:00"));

    List<Itinerary> itineraries =
        ItineraryBuilder.build(LegDeduplicator.deduplicate(offers), NOW).toList();

    assertThat(itineraries).extracting(Itinerary::id).hasSize(12).doesNotHaveDuplicates();
    assertThat(itineraries).filteredOn(itinerary -> itinerary.legs().size() == 1).hasSize(6);
    assertThat(itineraries).filteredOn(itinerary -> itinerary.legs().size() == 2).hasSize(6);
  }

  @Test
  void connectsToFlightsLeavingAtTheSameTime() {
    Leg sameTime = leg("AA101", "LHR-JFK", "2026-11-20T11:10+00:00", "2026-11-20T14:15-05:00");

    assertThat(onwardLegs(List.of(MAD_LHR, LHR_JFK, sameTime)))
        .containsExactlyInAnyOrder(LHR_JFK, sameTime);
  }

  /** The routes of every itinerary built from these offers, one per itinerary. */
  private static List<String> routes(Collection<Leg> offers) {
    return ItineraryBuilder.build(LegDeduplicator.deduplicate(offers), NOW)
        .map(ItineraryBuilderTest::route)
        .toList();
  }

  /** The second leg of every one-stop itinerary built from these offers. */
  private static List<Leg> onwardLegs(Collection<Leg> offers) {
    return ItineraryBuilder.build(LegDeduplicator.deduplicate(offers), NOW)
        .filter(itinerary -> itinerary.legs().size() == 2)
        .map(itinerary -> itinerary.legs().getLast())
        .toList();
  }

  /** An itinerary's airports in order, for example {@code MAD-LHR-JFK}. */
  private static String route(Itinerary itinerary) {
    return Stream.concat(
            Stream.of(itinerary.origin()), itinerary.legs().stream().map(Leg::destination))
        .map(Airport::code)
        .collect(Collectors.joining("-"));
  }
}
