package com.flightaggregator.adapter.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two slots of the itinerary double buffer. Every itinerary table and partition name the
 * repository writes into its SQL comes from here, never from input.
 */
enum Slot {
  A("a"),
  B("b");

  private final String code;
  private final String table;
  private final Pattern dayPartition;

  Slot(String code) {
    this.code = code;
    this.table = "itinerary_" + code;
    this.dayPartition = Pattern.compile(table + "_([0-9]{8})");
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
    return table;
  }

  /** The slot's partition of one departure day: {@code itinerary_<slot>_<yyyymmdd>}. */
  String partition(LocalDate day) {
    return table + "_" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
  }

  /** The partition for departures on days without one of their own. */
  String defaultPartition() {
    return table + "_default";
  }

  /**
   * The day of one of the slot's day partitions, read back from its name. Any other name is a
   * partition the repository did not create, and an {@link IllegalStateException}.
   */
  LocalDate dayOfPartition(String partition) {
    Matcher name = dayPartition.matcher(partition);
    if (name.matches()) {
      try {
        return LocalDate.parse(name.group(1), DateTimeFormatter.BASIC_ISO_DATE);
      } catch (DateTimeParseException notADay) {
        throw notCreatedHere(partition, notADay);
      }
    }
    throw notCreatedHere(partition, null);
  }

  private IllegalStateException notCreatedHere(String partition, DateTimeParseException cause) {
    return new IllegalStateException(
        table + " has a partition the repository did not create: " + partition, cause);
  }
}
