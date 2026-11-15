package com.flightaggregator.adapter.provider.gamma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class GammaPageParserTest {

  private static final Instant QUOTED_AT = Instant.parse("2026-11-15T10:00:00Z");

  private static final String HEADER =
      "airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n";

  private static final String VALID_ROW =
      "IB,3170,MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90\r\n";

  @Test
  void parsesAValidRow() {
    ParsedPage page = GammaPageParser.parse(HEADER + VALID_ROW, QUOTED_AT, 0, 100);

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

    ParsedPage page = GammaPageParser.parse(reordered, QUOTED_AT, 0, 100);

    assertThat(page.legs())
        .isEqualTo(GammaPageParser.parse(HEADER + VALID_ROW, QUOTED_AT, 0, 100).legs())
        .hasSize(1);
  }

  @Test
  void discardsEachOfTheMocksDefects() {
    String missingFlightNumber = VALID_ROW.replace("3170", "");
    String badDate = VALID_ROW.replace("2026-11-20T08:15:00+01:00", "2026-02-30T25:61:00+01:00");
    String negativePrice = VALID_ROW.replace("129.90", "-129.90");
    String unknownAirport = VALID_ROW.replace("LHR", "ZZZ");

    ParsedPage page =
        GammaPageParser.parse(
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

    ParsedPage page =
        GammaPageParser.parse(HEADER + tooFew + VALID_ROW + tooMany, QUOTED_AT, 0, 100);

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

    ParsedPage page = GammaPageParser.parse(HEADER + quoted + VALID_ROW, QUOTED_AT, 0, 100);

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

    assertThatThrownBy(() -> GammaPageParser.parse(withoutPrice, QUOTED_AT, 0, 100))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.GAMMA);
              assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE);
            });
  }

  @Test
  void continuesAtTheNextOffsetOnAFullPage() {
    String twoRows = HEADER + VALID_ROW + VALID_ROW;

    ParsedPage page = GammaPageParser.parse(twoRows, QUOTED_AT, 4, 2);

    assertThat(page.next()).contains("6");
    assertThat(page.announcedPages()).isEmpty();
  }

  @Test
  void endsOnAShortPageAndOnAHeaderOnlyPage() {
    ParsedPage shortPage = GammaPageParser.parse(HEADER + VALID_ROW, QUOTED_AT, 4, 2);
    ParsedPage headerOnly = GammaPageParser.parse(HEADER, QUOTED_AT, 6, 2);

    assertThat(shortPage.next()).isEmpty();
    assertThat(shortPage.legs()).hasSize(1);
    assertThat(headerOnly.next()).isEmpty();
    assertThat(headerOnly.legs()).isEmpty();
    assertThat(headerOnly.discarded()).isEmpty();
  }

  @Test
  void failsABodyWithoutAHeader() {
    assertThatThrownBy(() -> GammaPageParser.parse("", QUOTED_AT, 0, 100))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE));
    assertThatThrownBy(() -> GammaPageParser.parse(VALID_ROW, QUOTED_AT, 0, 100))
        .isInstanceOf(ProviderUnavailableException.class);
  }
}
