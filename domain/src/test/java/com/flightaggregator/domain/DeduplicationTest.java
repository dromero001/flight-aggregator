package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.leg;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeduplicationTest {

  @Test
  void rejectsTwoOffersOfTheSameFlight() {
    Leg leg = leg("IB3170", "MAD-LHR", "2026-11-20T08:15+01:00", "2026-11-20T09:30+00:00");

    assertThatThrownBy(() -> new Deduplication(List.of(leg, leg), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
