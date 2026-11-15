package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.ProviderText;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AlphaPageTest {

  private static final Instant QUOTED_AT = Instant.parse("2026-11-15T10:00:00Z");

  private static final String VALID_LEG =
      "{\"carrier\":\"IB\",\"flightNumber\":3170,\"origin\":\"MAD\","
          + "\"destination\":\"LHR\",\"departureTime\":\"2026-11-20T08:15:00+01:00\","
          + "\"arrivalTime\":\"2026-11-20T09:30:00+00:00\",\"price\":\"129.90\"}";

  @Test
  void parsesAValidLeg() {
    ParsedPage page = parse("{\"legs\":[" + VALID_LEG + "],\"nextCursor\":null}", QUOTED_AT);

    assertThat(page.legs())
        .containsExactly(
            new Leg(
                new Carrier("IB"),
                3170,
                new Airport("MAD"),
                new Airport("LHR"),
                OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
                OffsetDateTime.parse("2026-11-20T09:30:00+00:00"),
                Money.euros("129.90"),
                Provider.ALPHA,
                QUOTED_AT));
    assertThat(page.discarded()).isEmpty();
  }

  @Test
  void discardsEachOfTheMocksDefects() {
    String missingFlightNumber = VALID_LEG.replace("\"flightNumber\":3170,", "");
    String badDate = VALID_LEG.replace("2026-11-20T08:15:00+01:00", "2026-02-30T25:61:00+01:00");
    String negativePrice = VALID_LEG.replace("129.90", "-129.90");
    String unknownAirport = VALID_LEG.replace("LHR", "ZZZ");
    String body =
        "{\"legs\":["
            + String.join(
                ",",
                VALID_LEG,
                missingFlightNumber,
                badDate,
                negativePrice,
                unknownAirport,
                VALID_LEG)
            + "],\"nextCursor\":null}";

    ParsedPage page = parse(body, QUOTED_AT);

    assertThat(page.legs())
        .extracting(Leg::flightNumber, leg -> leg.destination().code())
        .containsExactly(tuple(3170, "LHR"), tuple(3170, "ZZZ"), tuple(3170, "LHR"));
    assertThat(page.discarded())
        .containsExactly(
            new DiscardedRecord(DiscardReason.FLIGHT_NUMBER, missingFlightNumber),
            new DiscardedRecord(DiscardReason.TIME, badDate),
            new DiscardedRecord(DiscardReason.PRICE, negativePrice));
  }

  @Test
  void discardsANumericPrice() {
    String numericPrice = VALID_LEG.replace("\"129.90\"", "129.9");

    ParsedPage page = parse(pageOf(numericPrice), QUOTED_AT);

    assertThat(page.legs()).isEmpty();
    assertThat(page.discarded())
        .containsExactly(new DiscardedRecord(DiscardReason.PRICE, numericPrice));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"IB3170\"", "7", "null", "[]", "true"})
  void discardsANonObjectRecord(String record) {
    ParsedPage page = parse(pageOf(VALID_LEG, record), QUOTED_AT);

    assertThat(page.legs()).hasSize(1);
    assertThat(page.discarded()).containsExactly(new DiscardedRecord(DiscardReason.SHAPE, record));
  }

  @ParameterizedTest
  @MethodSource("fieldsOfTheWrongJsonType")
  void discardsAFieldOfTheWrongJsonType(String field, String wrong, DiscardReason reason) {
    String record =
        VALID_LEG.replaceAll("\"" + field + "\":(\"[^\"]*\"|[0-9]+)", "\"" + field + "\":" + wrong);

    ParsedPage page = parse(pageOf(record), QUOTED_AT);

    assertThat(page.legs()).isEmpty();
    assertThat(page.discarded()).containsExactly(new DiscardedRecord(reason, record));
  }

  static Stream<Arguments> fieldsOfTheWrongJsonType() {
    return Stream.of(
        arguments("flightNumber", "\"3170\"", DiscardReason.FLIGHT_NUMBER),
        arguments("flightNumber", "31.5", DiscardReason.FLIGHT_NUMBER),
        arguments("carrier", "7", DiscardReason.CARRIER),
        arguments("origin", "[]", DiscardReason.AIRPORT),
        arguments("destination", "{}", DiscardReason.AIRPORT),
        arguments("departureTime", "20261120", DiscardReason.TIME),
        arguments("arrivalTime", "true", DiscardReason.TIME));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "", "[]", "null", "{}", "{\"legs\":5}"})
  void failsAPageThatIsNotJsonOrNotAlphasShape(String body) {
    assertThatThrownBy(() -> parse(body, QUOTED_AT)).isInstanceOf(PageMalformed.class);
  }

  /** A provider that sends more than it was asked for is not paging as agreed (§6.7). */
  @Test
  void failsAPageWithMoreRecordsThanTheLimit() {
    String threeRecords = pageOf(VALID_LEG, VALID_LEG, "7");

    assertThatThrownBy(
            () ->
                new AlphaPage(2, Optional.empty())
                    .parse(new FetchedPage(threeRecords, Provider.ALPHA, QUOTED_AT)))
        .isInstanceOf(PageMalformed.class)
        .hasMessage("Asked for at most 2 records, got 3");
  }

  @Test
  void readsTheNextCursorAndTheEnd() {
    assertThat(parse("{\"legs\":[],\"nextCursor\":\"b2Zmc2V0PTE=\"}", QUOTED_AT).next())
        .contains("b2Zmc2V0PTE=");
    assertThat(parse("{\"legs\":[],\"nextCursor\":null}", QUOTED_AT).next()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"legs\":[]}", "{\"legs\":[],\"nextCursor\":7}"})
  void failsAPageWhoseCursorIsNeitherAStringNorNull(String body) {
    assertThatThrownBy(() -> parse(body, QUOTED_AT))
        .isInstanceOfSatisfying(
            PageMalformed.class,
            e -> {
              assertThat(e).hasMessageContaining("nextCursor");
            });
  }

  @Test
  void cutsAHugeCursorInTheFailureMessage() {
    String body = "{\"legs\":[],\"nextCursor\":[\"" + "a".repeat(500) + "\"]}";

    assertThatThrownBy(() -> parse(body, QUOTED_AT))
        .isInstanceOfSatisfying(
            PageMalformed.class,
            e ->
                assertThat(e.getMessage())
                    .isEqualTo(
                        "The page's nextCursor is neither a string nor null: "
                            + ProviderText.quoted("[\"" + "a".repeat(500) + "\"]")));
  }

  private static String pageOf(String... records) {
    return "{\"legs\":[" + String.join(",", records) + "],\"nextCursor\":null}";
  }

  /** Parses an Alpha page of the first request, at the production page size. */
  private static ParsedPage parse(String body, Instant quotedAt) {
    return new AlphaPage(1000, Optional.empty())
        .parse(new FetchedPage(body, Provider.ALPHA, quotedAt));
  }
}
