package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.leg;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItineraryIdTest {

  @Test
  void derivesFromTheCanonicalLegKeys() {
    List<LegKey> keys = keysOf("IB3170");

    assertThat(ItineraryId.of(keys))
        .isEqualTo(
            new ItineraryId(
                UUID.nameUUIDFromBytes(
                    "IB3170 MAD-LHR 2026-11-20T07:15:00Z|BA117 LHR-JFK 2026-11-20T11:10:00Z"
                        .getBytes(UTF_8))));
  }

  @Test
  void differsForDifferentFlights() {
    assertThat(ItineraryId.of(keysOf("IB3170"))).isNotEqualTo(ItineraryId.of(keysOf("IB3172")));
  }

  private static List<LegKey> keysOf(String firstFlight) {
    return List.of(
        leg(firstFlight, "MAD-LHR", "2026-11-20T08:15+01:00", "2026-11-20T09:30+00:00").key(),
        leg("BA117", "LHR-JFK", "2026-11-20T11:10+00:00", "2026-11-20T14:20-05:00").key());
  }
}
