package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.offer;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegDeduplicatorTest {

  private static final String DEPARTURE = "2026-11-20T08:15+01:00";
  private static final String ARRIVAL = "2026-11-20T09:30+00:00";

  @Test
  void keepsTheCheapestOfferOfEachFlight() {
    List<Leg> offers =
        List.of(
            offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "120.00", Provider.ALPHA),
            offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "99.90", Provider.BETA),
            offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "130.00", Provider.GAMMA));

    assertThat(LegDeduplicator.deduplicate(offers)).containsExactly(offers.get(1));
  }

  @Test
  void keepsDistinctFlights() {
    List<Leg> offers =
        List.of(
            offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "100.00", Provider.ALPHA),
            offer("IB3172", "MAD-LHR", DEPARTURE, ARRIVAL, "100.00", Provider.ALPHA));

    assertThat(LegDeduplicator.deduplicate(offers)).containsExactlyElementsOf(offers);
  }

  @Test
  void keepsTheSameOfferOnce() {
    Leg offer = offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "100.00", Provider.ALPHA);

    assertThat(LegDeduplicator.deduplicate(List.of(offer, offer))).containsExactly(offer);
  }

  @Test
  void prefersTheLatestQuoteAtTheSamePrice() {
    Leg alpha = offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "99.90", Provider.ALPHA);
    Leg beta = Legs.requoted(alpha, "99.90", Provider.BETA, Instant.parse("2026-11-19T11:30:00Z"));

    assertThat(LegDeduplicator.deduplicate(List.of(alpha, beta))).containsExactly(beta);
    assertThat(LegDeduplicator.deduplicate(List.of(beta, alpha))).containsExactly(beta);
  }

  @Test
  void breaksFullTiesByProviderOrder() {
    Leg gamma = offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "99.90", Provider.GAMMA);
    Leg alpha = offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "99.90", Provider.ALPHA);

    assertThat(LegDeduplicator.deduplicate(List.of(gamma, alpha))).containsExactly(alpha);
    assertThat(LegDeduplicator.deduplicate(List.of(alpha, gamma))).containsExactly(alpha);
  }

  @Test
  void prefersTheCheapestOfferOverTheLatestQuote() {
    Leg alpha =
        Legs.requoted(
            offer("IB3170", "MAD-LHR", DEPARTURE, ARRIVAL, "99.90", Provider.ALPHA),
            "99.90",
            Provider.ALPHA,
            Instant.parse("2026-11-19T10:00:00Z"));
    Leg beta = Legs.requoted(alpha, "120.00", Provider.BETA, Instant.parse("2026-11-19T11:30:00Z"));

    assertThat(LegDeduplicator.deduplicate(List.of(alpha, beta))).containsExactly(alpha);
    assertThat(LegDeduplicator.deduplicate(List.of(beta, alpha))).containsExactly(alpha);
  }
}
