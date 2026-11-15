package com.flightaggregator.adapter.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The one definition of an itinerary's partition day (R12): the UTC date of its departure instant,
 * whatever offset the provider quoted it in. Public so that the partitions' metrics count days the
 * same way.
 */
public final class PartitionCalendar {

  private PartitionCalendar() {}

  public static LocalDate dayOf(Instant departure) {
    return LocalDate.ofInstant(departure, ZoneOffset.UTC);
  }

  /** The first instant of the day's partition, its UTC midnight; the next day's is its end. */
  static Instant start(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
