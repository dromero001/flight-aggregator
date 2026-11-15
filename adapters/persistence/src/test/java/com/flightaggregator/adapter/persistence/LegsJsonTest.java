package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonFactory;

class LegsJsonTest {

  private static final JsonFactory JSON = new JsonFactory();

  private static final Leg MAD_LHR =
      new Leg(
          new Carrier("IB"),
          3166,
          new Airport("MAD"),
          new Airport("LHR"),
          OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
          OffsetDateTime.parse("2026-11-20T09:30:00+00:00"),
          Money.euros("0.01"),
          ALPHA,
          Instant.parse("2026-11-15T10:00:00.123456Z"));

  private static final Leg LHR_JFK =
      new Leg(
          new Carrier("BA"),
          117,
          new Airport("LHR"),
          new Airport("JFK"),
          OffsetDateTime.parse("2026-11-20T11:00:00+00:00"),
          OffsetDateTime.parse("2026-11-20T14:00:00-05:00"),
          Money.euros("450.10"),
          BETA,
          Instant.parse("2026-11-15T09:59:00Z"));

  @Test
  void roundTripsLegsExactly() {
    List<Leg> legs = List.of(MAD_LHR, LHR_JFK);

    List<Leg> read = LegsJson.read(LegsJson.write(legs, JSON), JSON);

    assertThat(read).isEqualTo(legs);
    assertThat(new Itinerary(read).id()).isEqualTo(new Itinerary(legs).id());
  }

  @Test
  void failsOnADocumentOfAnotherShape() {
    assertThatThrownBy(() -> LegsJson.read("{\"carrier\":\"IB\"}", JSON))
        .isInstanceOf(IllegalStateException.class);
  }
}
