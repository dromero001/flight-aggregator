package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KeysetQueryTest {

  private static final UUID ID = UUID.fromString("0f8e3a52-1c3b-4b6e-9d2a-5b7c8e9f0a1b");

  private static final SeekPosition AFTER =
      new SeekPosition(
          Money.euros("120.50"),
          Instant.parse("2026-11-25T07:15:00Z"),
          Duration.ofHours(2),
          new ItineraryId(ID));

  static Stream<Arguments> sortOrders() {
    return Stream.of(
        arguments(SortOrder.PRICE, "price", new BigDecimal("120.50")),
        arguments(SortOrder.DEPARTURE, "departure", OffsetDateTime.parse("2026-11-25T07:15:00Z")),
        arguments(SortOrder.DURATION, "duration_seconds", 7_200L));
  }

  @ParameterizedTest
  @MethodSource("sortOrders")
  void buildsTheQueryOfEachSortWithEveryFilter(SortOrder sort, String key, Object seekValue) {
    SearchCriteria criteria =
        new SearchCriteria(
            Optional.of(new Airport("MAD")),
            Optional.of(new Airport("LHR")),
            Optional.of(Money.euros("250.00")),
            Optional.of(new Carrier("IB")),
            sort);

    KeysetQuery query =
        KeysetQuery.of(
            Slot.B, criteria, Optional.of(AFTER), 21, Instant.parse("2026-11-20T10:00:00Z"));

    assertThat(query.sql())
        .isEqualTo(
            "SELECT legs FROM itinerary_b WHERE departure > ? AND origin = ? AND destination = ?"
                + " AND price <= ? AND single_carrier = ? AND ("
                + key
                + ", id) > (?, ?) ORDER BY "
                + key
                + ", id LIMIT ?");
    assertThat(query.parameters())
        .containsExactly(
            OffsetDateTime.parse("2026-11-20T10:00:00Z"),
            "MAD",
            "LHR",
            new BigDecimal("250.00"),
            "IB",
            seekValue,
            ID,
            21);
  }
}
