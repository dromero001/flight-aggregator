package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonFactory;

class ItineraryRowTest {

  private static final JsonFactory JSON = new JsonFactory();

  private static final Leg MAD_LHR =
      leg(
          "IB",
          3166,
          "MAD",
          "LHR",
          "2026-11-20T08:15:00+01:00",
          "2026-11-20T09:30:00+00:00",
          "129.90",
          ALPHA,
          "2026-11-15T10:00:00.123456Z");

  private static final Leg LHR_JFK =
      leg(
          "BA",
          117,
          "LHR",
          "JFK",
          "2026-11-20T11:00:00+00:00",
          "2026-11-20T14:00:00-05:00",
          "450.10",
          BETA,
          "2026-11-15T09:59:00Z");

  private static Leg leg(
      String carrier,
      int number,
      String origin,
      String destination,
      String departure,
      String arrival,
      String price,
      Provider provider,
      String quotedAt) {
    return new Leg(
        new Carrier(carrier),
        number,
        new Airport(origin),
        new Airport(destination),
        OffsetDateTime.parse(departure),
        OffsetDateTime.parse(arrival),
        Money.euros(price),
        provider,
        Instant.parse(quotedAt));
  }

  @Test
  void writesTheCellsOfATwoLegItinerary() {
    Itinerary interline = new Itinerary(List.of(MAD_LHR, LHR_JFK));

    String[] cells = ItineraryRow.of(interline).cells(JSON);

    // The legs, the thirteenth cell, are pinned by writesTheLegsAsJson.
    assertThat(cells).hasSize(13);
    assertThat(Arrays.asList(cells).subList(0, 12))
        .containsExactly(
            interline.id().value().toString(),
            "2026-11-20T07:15:00Z",
            "3600",
            "2026-11-20T19:00:00Z",
            "-18000",
            "MAD",
            "JFK",
            "42300",
            "1",
            null,
            "609.00",
            "2026-11-15T09:59:00Z");
  }

  @Test
  void writesTheLegsAsJson() {
    String[] cells = ItineraryRow.of(new Itinerary(List.of(MAD_LHR))).cells(JSON);

    assertThat(cells[12])
        .isEqualTo(
            "[{\"carrier\":\"IB\",\"flightNumber\":3166,\"origin\":\"MAD\",\"destination\":\"LHR\","
                + "\"departure\":\"2026-11-20T08:15:00+01:00\",\"arrival\":\"2026-11-20T09:30:00+00:00\","
                + "\"price\":\"129.90\",\"provider\":\"ALPHA\","
                + "\"quotedAt\":\"2026-11-15T10:00:00.123456Z\"}]");
  }
}
