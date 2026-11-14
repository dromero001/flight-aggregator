package com.flightaggregator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConnectionRulesTest {

  private static final Leg INBOUND =
      Legs.leg("IB3166", "MAD-LHR", "2026-11-20T08:00+00:00", "2026-11-20T10:00+00:00");

  private static Leg outboundFromLhrAt(String departure) {
    return Legs.leg("BA0422", "LHR-BCN", departure, "2026-11-22T09:00+00:00");
  }

  @Test
  void connectsAfterExactlyTheMinimumConnectionTime() {
    assertThat(ConnectionRules.connects(INBOUND, outboundFromLhrAt("2026-11-20T10:45+00:00")))
        .isTrue();
  }

  @Test
  void rejectsAConnectionUnderTheMinimum() {
    assertThat(ConnectionRules.connects(INBOUND, outboundFromLhrAt("2026-11-20T10:44+00:00")))
        .isFalse();
  }

  @Test
  void acceptsALayoverOfExactly24Hours() {
    assertThat(ConnectionRules.connects(INBOUND, outboundFromLhrAt("2026-11-21T10:00+00:00")))
        .isTrue();
    assertThat(ConnectionRules.connects(INBOUND, outboundFromLhrAt("2026-11-21T10:01+00:00")))
        .isFalse();
  }

  @Test
  void rejectsADifferentAirport() {
    Leg fromGatwick =
        Legs.leg("BA0422", "LGW-BCN", "2026-11-20T12:00+00:00", "2026-11-20T15:00+00:00");

    assertThat(ConnectionRules.connects(INBOUND, fromGatwick)).isFalse();
  }

  @Test
  void rejectsAConnectionThatLooksLongEnoughInLocalTime() {
    Leg inbound = Legs.leg("BA0421", "LHR-MAD", "2026-03-29T00:30+01:00", "2026-03-29T01:50+01:00");
    Leg outbound =
        Legs.leg("IB3167", "MAD-BCN", "2026-03-29T03:20+02:00", "2026-03-29T04:30+02:00");

    assertThat(ConnectionRules.connects(inbound, outbound)).isFalse();
  }

  @Test
  void acceptsAConnectionThatLooksTooShortInLocalTime() {
    Leg inbound = Legs.leg("BA0421", "LHR-MAD", "2026-10-25T00:30+02:00", "2026-10-25T02:30+02:00");
    Leg outbound =
        Legs.leg("IB3167", "MAD-BCN", "2026-10-25T02:15+01:00", "2026-10-25T03:30+01:00");

    assertThat(ConnectionRules.connects(inbound, outbound)).isTrue();
  }

  @Test
  void sellsOnlyLegsDepartingAfterNow() {
    Instant now = Instant.parse("2026-11-19T12:00:00Z");

    assertThat(ConnectionRules.isSellable(departingAt("2026-11-19T13:01:00+01:00"), now)).isTrue();
    assertThat(ConnectionRules.isSellable(departingAt("2026-11-19T13:00:00+01:00"), now)).isFalse();
    assertThat(ConnectionRules.isSellable(departingAt("2026-11-19T12:59:00+01:00"), now)).isFalse();
  }

  private static Leg departingAt(String departure) {
    return Legs.leg("IB3166", "MAD-LHR", departure, "2026-11-19T16:00:00+01:00");
  }
}
