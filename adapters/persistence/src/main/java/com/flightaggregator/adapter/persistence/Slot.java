package com.flightaggregator.adapter.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The two slots of the itinerary double buffer. Every itinerary table and partition name the
 * repository writes into its SQL comes from here, never from input.
 */
enum Slot {
  A("a"),
  B("b");

  private final String code;

  Slot(String code) {
    this.code = code;
  }

  /** The slot {@code inventory_publication.active_slot} names. */
  static Slot of(String code) {
    return valueOf(code.toUpperCase(Locale.ROOT));
  }

  /** The slot as {@code inventory_publication.active_slot} holds it. */
  String code() {
    return code;
  }

  Slot other() {
    return this == A ? B : A;
  }

  String table() {
    return "itinerary_" + code;
  }

  /** The slot's partition of one departure day: {@code itinerary_<slot>_<yyyymmdd>}. */
  String partition(LocalDate day) {
    return table() + "_" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
  }

  /** The partition for departures on days without one of their own. */
  String defaultPartition() {
    return table() + "_default";
  }

  /** The day of one of the slot's day partitions, read back from its name. */
  LocalDate dayOfPartition(String partition) {
    return LocalDate.parse(
        partition.substring(table().length() + 1), DateTimeFormatter.BASIC_ISO_DATE);
  }
}
