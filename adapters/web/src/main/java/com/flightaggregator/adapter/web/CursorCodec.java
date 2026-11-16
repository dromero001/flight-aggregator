package com.flightaggregator.adapter.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.flightaggregator.application.port.in.InvalidCursorException;
import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Money;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The opaque cursor of the next page (§5.4): base64url, without padding, of {@code
 * 1|<generation>|<sort>|<price>|<departure instant>|<duration seconds>|<id>}. It is not signed (E3
 * was rejected): a client can only seek within public data, so decoding accepts each field in the
 * form {@link #encode} writes it, up to leading zeros and the length of a fraction of a second, and
 * refuses everything else, so that no value reaches the database that it cannot store (departures
 * stay within the years 1000 to 9999). Those looser forms decode to the seek position the issued
 * cursor names.
 */
final class CursorCodec {

  /** The longest text decoded; the fields' own forms keep a real cursor under 180 characters. */
  static final int MAXIMUM_LENGTH = 200;

  /** The base64url alphabet without padding, and a bound on the input before it is decoded. */
  private static final Pattern ENCODED = Pattern.compile("[A-Za-z0-9_-]{1," + MAXIMUM_LENGTH + "}");

  /** Version 1, then each field as {@link #encode} writes it. */
  private static final Pattern TEXT =
      Pattern.compile(
          "1\\|([0-9]{1,19})\\|("
              + Arrays.stream(SortOrder.values()).map(Enum::name).collect(joining("|"))
              + ")\\|([0-9]{1,9}\\.[0-9]{2})"
              + "\\|([1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z)"
              + "\\|([0-9]{1,19})"
              + "\\|([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private CursorCodec() {}

  static String encode(PageCursor cursor) {
    SeekPosition position = cursor.position();
    String text =
        String.join(
            "|",
            "1",
            Long.toString(cursor.generation()),
            cursor.sort().name(),
            position.price().amount().toPlainString(),
            position.departure().toString(),
            Long.toString(position.duration().toSeconds()),
            position.id().value().toString());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(UTF_8));
  }

  /**
   * @throws InvalidCursorException when the text is not a cursor {@link #encode} wrote
   */
  static PageCursor decode(String cursor) {
    if (!ENCODED.matcher(cursor).matches()) {
      throw notACursor();
    }
    Matcher fields;
    try {
      fields = TEXT.matcher(new String(Base64.getUrlDecoder().decode(cursor), UTF_8));
    } catch (IllegalArgumentException notBase64) {
      throw notACursor();
    }
    if (!fields.matches()) {
      throw notACursor();
    }
    try {
      return new PageCursor(
          Long.parseLong(fields.group(1)),
          SortOrder.valueOf(fields.group(2)),
          new SeekPosition(
              Money.euros(fields.group(3)),
              Instant.parse(fields.group(4)),
              Duration.ofSeconds(Long.parseLong(fields.group(5))),
              new ItineraryId(UUID.fromString(fields.group(6)))));
    } catch (NumberFormatException | DateTimeException outOfRange) {
      throw notACursor();
    }
  }

  private static InvalidCursorException notACursor() {
    return new InvalidCursorException(
        "The cursor is not one this search issued: start the search again without it.");
  }
}
