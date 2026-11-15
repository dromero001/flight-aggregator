package com.flightaggregator.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LegKeyTest {

  private static final Carrier CARRIER = new Carrier("IB");
  private static final Airport MAD = new Airport("MAD");
  private static final Airport LHR = new Airport("LHR");
  private static final Instant DEPARTURE = Instant.parse("2026-11-20T07:15:00Z");

  @Test
  void rejectsANullComponent() {
    assertThatThrownBy(() -> new LegKey(null, 3170, MAD, LHR, DEPARTURE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("carrier");
    assertThatThrownBy(() -> new LegKey(CARRIER, 3170, null, LHR, DEPARTURE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("origin");
    assertThatThrownBy(() -> new LegKey(CARRIER, 3170, MAD, null, DEPARTURE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("destination");
    assertThatThrownBy(() -> new LegKey(CARRIER, 3170, MAD, LHR, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("departure");
  }
}
