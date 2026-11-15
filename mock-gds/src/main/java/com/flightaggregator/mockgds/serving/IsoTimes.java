package com.flightaggregator.mockgds.serving;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The time format of all three providers: ISO-8601 with a numeric offset and always with seconds,
 * so UTC is written {@code +00:00}, never {@code Z}.
 */
public final class IsoTimes {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

  private IsoTimes() {}

  public static String format(OffsetDateTime time) {
    return FORMAT.format(time);
  }
}
