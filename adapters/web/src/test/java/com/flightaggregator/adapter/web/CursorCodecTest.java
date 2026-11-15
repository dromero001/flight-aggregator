package com.flightaggregator.adapter.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.flightaggregator.application.port.in.InvalidCursorException;
import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SortOrder;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class CursorCodecTest {

  private static final String ID = Cursors.ID;

  /**
   * The plan's format, base64url without padding, and back to the same cursor: {@link Cursors}'
   * page ends at 129.90 euros and 7 h 25 min (26,700 s).
   */
  @ParameterizedTest
  @CsvSource({
    "PRICE, 2026-11-20T07:15:00Z, 1|7|PRICE|129.90|2026-11-20T07:15:00Z|26700|" + ID,
    "DEPARTURE, 2026-11-20T07:15:00.250Z, 1|7|DEPARTURE|129.90|2026-11-20T07:15:00.250Z|26700|"
        + ID,
    "DURATION, 2026-11-20T07:15:00Z, 1|7|DURATION|129.90|2026-11-20T07:15:00Z|26700|" + ID
  })
  void roundTrips(SortOrder sort, String departure, String text) {
    PageCursor cursor = Cursors.cursor(7, sort, Instant.parse(departure));

    String encoded = CursorCodec.encode(cursor);

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
    assertThat(new String(Base64.getUrlDecoder().decode(encoded), UTF_8)).isEqualTo(text);
    assertThat(CursorCodec.decode(encoded)).isEqualTo(cursor);
  }

  /**
   * Review Focus 2: anything but a cursor this codec wrote is refused, never decoded into a seek
   * position the database cannot take (a 500) or that the client made up in another form.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("notCursors")
  void rejectsGarbageTamperedAndOverlongCursors(String description, String cursor) {
    assertThatThrownBy(() -> CursorCodec.decode(cursor))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("cursor");
  }

  static Stream<Arguments> notCursors() {
    String valid = "1|7|PRICE|129.90|2026-11-20T07:15:00Z|26700|" + ID;
    return Stream.of(
        arguments("characters outside base64url", "not a cursor!"),
        arguments("empty", ""),
        arguments("a length base64 cannot have", "AAAAA"),
        arguments("base64url of something else", encoded("hello")),
        arguments("padded", encoded(valid) + "="),
        arguments("another version", encoded(valid.replaceFirst("^1", "2"))),
        arguments("an unknown sort order", encoded(valid.replace("PRICE", "BEST"))),
        arguments("a price in exponent form", encoded(valid.replace("129.90", "1E+3"))),
        arguments("a negative price", encoded(valid.replace("129.90", "-1.00"))),
        arguments("month 13", encoded(valid.replace("2026-11-20", "2026-13-20"))),
        arguments(
            "a year the database cannot store",
            encoded(valid.replace("2026-11-20", "+100000-11-20"))),
        arguments("the year 0, before the common era", encoded(valid.replace("2026", "0000"))),
        arguments(
            "a generation beyond a long", encoded(valid.replace("|7|", "|99999999999999999999|"))),
        arguments("an id in UUID's lenient form", encoded(valid.replace(ID, "1-1-1-1-1"))),
        arguments("a field missing", encoded(valid.replace("|26700", ""))),
        arguments("a field more", encoded(valid + "|1")),
        arguments("over 200 characters", "A".repeat(201)));
  }

  private static String encoded(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(UTF_8));
  }
}
