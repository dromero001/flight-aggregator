package com.flightaggregator.adapter.provider;

import static com.flightaggregator.adapter.provider.Fixtures.FLIGHT;
import static com.flightaggregator.adapter.provider.Fixtures.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GammaPageTest {

  private static final Instant QUOTED_AT = Instant.parse("2026-11-15T10:00:00Z");

  private static final String HEADER =
      "airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n";

  private static final String VALID_ROW =
      "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90\r\n";

  @Test
  void parsesAValidRow() {
    ParsedPage page = parse(HEADER + VALID_ROW, QUOTED_AT, 0, 100);

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
                Provider.GAMMA,
                QUOTED_AT));
    assertThat(page.discarded()).isEmpty();
  }

  @Test
  void readsColumnsByHeaderName() {
    String reordered =
        "price_eur,to,from,arrives_at,departs_at,flight_number,airline\r\n"
            + "129.90,LHR,MAD,2026-11-20T09:30:00+00:00,2026-11-20T08:15:00+01:00,3170,IB\r\n";

    ParsedPage page = parse(reordered, QUOTED_AT, 0, 100);

    assertThat(page.legs())
        .isEqualTo(parse(HEADER + VALID_ROW, QUOTED_AT, 0, 100).legs())
        .hasSize(1);
  }

  @Test
  void discardsEachOfTheMocksDefects() {
    String missingFlightNumber = VALID_ROW.replace("3170", "");
    String badDate = VALID_ROW.replace("2026-11-20T08:15:00+01:00", "2026-02-30T25:61:00+01:00");
    String negativePrice = VALID_ROW.replace("129.90", "-129.90");
    String unknownAirport = VALID_ROW.replace("LHR", "ZZZ");

    ParsedPage page =
        parse(
            HEADER + VALID_ROW + missingFlightNumber + badDate + negativePrice + unknownAirport,
            QUOTED_AT,
            0,
            100);

    assertThat(page.legs())
        .extracting(Leg::flightNumber, leg -> leg.destination().code())
        .containsExactly(tuple(3170, "LHR"), tuple(3170, "ZZZ"));
    assertThat(page.discarded())
        .containsExactly(
            new DiscardedRecord(
                DiscardReason.FLIGHT_NUMBER,
                "IB,,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90"),
            new DiscardedRecord(
                DiscardReason.TIME,
                "IB,3170,MAD,LHR,2026-02-30T25:61:00+01:00,2026-11-20T09:30:00+00:00,129.90"),
            new DiscardedRecord(
                DiscardReason.PRICE,
                "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,-129.90"));
  }

  @Test
  void discardsARowWithTheWrongCellCount() {
    String tooFew = "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00\r\n";
    String tooMany = VALID_ROW.replace("\r\n", ",extra\r\n");

    ParsedPage page = parse(HEADER + tooFew + VALID_ROW + tooMany, QUOTED_AT, 0, 100);

    assertThat(page.legs()).hasSize(1);
    assertThat(page.discarded())
        .containsExactly(
            new DiscardedRecord(
                DiscardReason.SHAPE,
                "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00"),
            new DiscardedRecord(
                DiscardReason.SHAPE,
                "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90,extra"));
  }

  @Test
  void discardsALineWithAQuotedCellAlone() {
    String quoted = VALID_ROW.replace("IB", "\"IB\"");

    ParsedPage page = parse(HEADER + quoted + VALID_ROW, QUOTED_AT, 0, 100);

    assertThat(page.legs()).hasSize(1);
    assertThat(page.discarded())
        .containsExactly(
            new DiscardedRecord(
                DiscardReason.SHAPE,
                "\"IB\",3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90"));
  }

  @Test
  void failsAPageWithoutARequiredColumn() {
    String withoutPrice =
        "airline,flight_number,from,to,departs_at,arrives_at\r\n"
            + "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00\r\n";

    assertThatThrownBy(() -> parse(withoutPrice, QUOTED_AT, 0, 100))
        .isInstanceOf(PageMalformed.class);
  }

  @Test
  void continuesAtTheNextOffsetOnAFullPage() {
    String twoRows = HEADER + VALID_ROW + VALID_ROW;

    ParsedPage page = parse(twoRows, QUOTED_AT, 4, 2);

    assertThat(page.next()).contains("6");
    assertThat(page.announcedPages()).isEmpty();
  }

  @Test
  void endsOnAShortPageAndOnAHeaderOnlyPage() {
    ParsedPage shortPage = parse(HEADER + VALID_ROW, QUOTED_AT, 4, 2);
    ParsedPage headerOnly = parse(HEADER, QUOTED_AT, 6, 2);

    assertThat(shortPage.next()).isEmpty();
    assertThat(shortPage.legs()).hasSize(1);
    assertThat(headerOnly.next()).isEmpty();
    assertThat(headerOnly.legs()).isEmpty();
    assertThat(headerOnly.discarded()).isEmpty();
  }

  @Test
  void failsABodyWithoutAHeader() {
    assertThatThrownBy(() -> parse("", QUOTED_AT, 0, 100)).isInstanceOf(PageMalformed.class);
    assertThatThrownBy(() -> parse(VALID_ROW, QUOTED_AT, 0, 100)).isInstanceOf(PageMalformed.class);
  }

  @Test
  void failsABodyOfOnlyLineBreaksAsAMissingHeader() {
    for (String body : new String[] {"\r\n", "\r\n\r\n"}) {
      assertThatThrownBy(() -> parse(body, QUOTED_AT, 0, 100)).isInstanceOf(PageMalformed.class);
    }
  }

  @Test
  void discardsABlankLineAtTheEndOfAPageAndCountsItTowardTheLimit() {
    ParsedPage page = parse(HEADER + VALID_ROW + "\r\n", QUOTED_AT, 0, 2);

    assertThat(page.legs()).hasSize(1);
    assertThat(page.discarded()).containsExactly(new DiscardedRecord(DiscardReason.SHAPE, ""));
    assertThat(page.next()).contains("2");
  }

  @Test
  void failsAPageWithMoreRowsThanTheLimit() {
    String threeRows = HEADER + VALID_ROW + VALID_ROW + VALID_ROW;

    assertThatThrownBy(() -> parse(threeRows, QUOTED_AT, 0, 2))
        .isInstanceOfSatisfying(
            PageMalformed.class,
            e -> {
              assertThat(e).hasMessage("Asked for at most 2 rows, got 3");
            });
  }

  @Test
  void continuesAfterAFullPageThatHoldsADiscardedLine() {
    String badPrice = VALID_ROW.replace("129.90", "-129.90");

    ParsedPage page = parse(HEADER + VALID_ROW + badPrice, QUOTED_AT, 0, 2);

    assertThat(page.legs()).hasSize(1);
    assertThat(page.discarded()).hasSize(1);
    assertThat(page.next()).contains("2");
  }

  @Test
  void failsAHeaderThatRepeatsAColumn() {
    String repeated =
        "airline,flight_number,from,to,departs_at,arrives_at,price_eur,price_eur\r\n"
            + VALID_ROW.replace("\r\n", ",1\r\n");

    assertThatThrownBy(() -> parse(repeated, QUOTED_AT, 0, 100)).isInstanceOf(PageMalformed.class);
  }

  @Test
  void failsAPageWithABareLineBreakInADataLine() {
    for (String separator : new String[] {"\n", "\r"}) {
      String body = HEADER + VALID_ROW.replace(",129.90", separator + "129.90") + VALID_ROW;

      assertThatThrownBy(() -> parse(body, QUOTED_AT, 0, 100)).isInstanceOf(PageMalformed.class);
    }
  }

  @Test
  void failsAPageWithABareLineBreakInTheHeader() {
    String body = HEADER.replace("\r\n", ",note\nbreak\r\n") + VALID_ROW.replace("\r\n", ",1\r\n");

    assertThatThrownBy(() -> parse(body, QUOTED_AT, 0, 100)).isInstanceOf(PageMalformed.class);
  }

  @Test
  void quotesTheHeaderInTheMissingColumnMessage() {
    String body = "airline\u0085,flight_number\r\n";

    assertThatThrownBy(() -> parse(body, QUOTED_AT, 0, 100))
        .isInstanceOfSatisfying(
            PageMalformed.class,
            e ->
                assertThat(e)
                    .hasMessage(
                        "The header \"airline\\u0085,flight_number\" has no column airline"));
  }

  /** The origin joins the listing's query (Task 1), after the offset and limit. */
  @Test
  void requestsOnlyAnOriginsLegs() {
    PageRequest request =
        GammaPage.FORMAT.at(Optional.of(new Airport("MAD")), Optional.of("200"), 100);

    assertThat(path(request)).isEqualTo("/gamma/legs.csv?offset=200&limit=100&origin=MAD");
  }

  /**
   * A quote is the listing with the flight's five key parts (Task 1), the departure a UTC instant
   * ending in {@code Z}, at offset 0 with a limit of one record: the most a quote may answer.
   */
  @Test
  void buildsTheQuotePath() {
    PageRequest request = GammaPage.FORMAT.quoting(FLIGHT);

    assertThat(path(request))
        .isEqualTo(
            "/gamma/legs.csv?offset=0&limit=1&carrier=IB&flightNumber=3170&origin=MAD"
                + "&destination=LHR&departure=2026-11-20T07%3A15%3A00Z");
  }

  /** Parses the Gamma page at the requested offset and limit. */
  private static ParsedPage parse(String body, Instant quotedAt, int offset, int limit) {
    return new GammaPage(ListingQuery.from(Optional.empty()), offset, limit)
        .parse(new FetchedPage(body, Provider.GAMMA, quotedAt));
  }
}
