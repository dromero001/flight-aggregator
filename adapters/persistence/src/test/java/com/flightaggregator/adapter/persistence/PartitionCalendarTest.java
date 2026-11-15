package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PartitionCalendarTest {

  @Test
  void partitionsByTheUtcDayOfTheDeparture() {
    assertThat(
            PartitionCalendar.dayOf(OffsetDateTime.parse("2026-11-20T00:30:00+01:00").toInstant()))
        .isEqualTo(LocalDate.parse("2026-11-19"));
    assertThat(
            PartitionCalendar.dayOf(OffsetDateTime.parse("2026-11-19T23:30:00-05:00").toInstant()))
        .isEqualTo(LocalDate.parse("2026-11-20"));
  }

  @Test
  void startsADayAtItsUtcMidnight() {
    assertThat(PartitionCalendar.start(LocalDate.parse("2026-11-20")))
        .isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
  }
}
