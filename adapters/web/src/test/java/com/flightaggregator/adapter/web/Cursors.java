package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Test fixture: the cursor of a page that ended at one fixed itinerary. */
final class Cursors {

  static final String ID = "3f2c8f4e-6b1a-3c55-9d27-0a8e4b6c1f90";

  private Cursors() {}

  /** At 129.90 euros, departing 2026-11-20T07:15Z, flying 7 h 25 min. */
  static PageCursor cursor(long generation, SortOrder sort) {
    return cursor(generation, sort, Instant.parse("2026-11-20T07:15:00Z"));
  }

  static PageCursor cursor(long generation, SortOrder sort, Instant departure) {
    return new PageCursor(
        generation,
        sort,
        new SeekPosition(
            Money.euros("129.90"),
            departure,
            Duration.ofMinutes(445),
            new ItineraryId(UUID.fromString(ID))));
  }
}
