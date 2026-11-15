package com.flightaggregator.adapter.provider.beta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BetaPageParserTest {

  private static final Instant QUOTED_AT = Instant.parse("2026-11-15T10:00:00Z");

  private static final String VALID_SEGMENT =
      "<Segment><Carrier code=\"IB\"/><FlightNumber>3170</FlightNumber>"
          + "<Departure airport=\"MAD\">2026-11-20T08:15:00+01:00</Departure>"
          + "<Arrival airport=\"LHR\">2026-11-20T09:30:00+00:00</Arrival>"
          + "<PriceCents>12990</PriceCents></Segment>";

  @Test
  void parsesAValidSegment() {
    ParsedPage page = BetaPageParser.parse(pageOf(1, 1, VALID_SEGMENT), QUOTED_AT);

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
                Provider.BETA,
                QUOTED_AT));
    assertThat(page.discarded()).isEmpty();
  }

  @Test
  void discardsEachOfTheMocksDefects() {
    String missingFlightNumber = VALID_SEGMENT.replace("<FlightNumber>3170</FlightNumber>", "");
    String badDate =
        VALID_SEGMENT.replace("2026-11-20T08:15:00+01:00", "2026-02-30T25:61:00+01:00");
    String negativePrice = VALID_SEGMENT.replace("12990", "-12990");
    String unknownAirport = VALID_SEGMENT.replace("LHR", "ZZZ");

    ParsedPage page =
        BetaPageParser.parse(
            pageOf(
                1,
                1,
                VALID_SEGMENT,
                missingFlightNumber,
                badDate,
                negativePrice,
                unknownAirport,
                VALID_SEGMENT),
            QUOTED_AT);

    assertThat(page.legs())
        .extracting(Leg::flightNumber, leg -> leg.destination().code())
        .containsExactly(tuple(3170, "LHR"), tuple(3170, "ZZZ"), tuple(3170, "LHR"));
    assertThat(page.discarded())
        .containsExactly(
            new DiscardedRecord(
                DiscardReason.FLIGHT_NUMBER,
                "carrier=IB origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                    + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
            new DiscardedRecord(
                DiscardReason.TIME,
                "carrier=IB flightNumber=3170 origin=MAD departureTime=2026-02-30T25:61:00+01:00 "
                    + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
            new DiscardedRecord(
                DiscardReason.PRICE,
                "carrier=IB flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                    + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=-12990"));
  }

  @Test
  void refusesADoctypeAndExternalEntities(@TempDir Path dir) throws IOException {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET");
    String body =
        "<!DOCTYPE SegmentsResponse [<!ENTITY leak SYSTEM \""
            + secret.toUri()
            + "\">]>"
            + pageOf(1, 1, VALID_SEGMENT.replace("3170", "&leak;"));

    assertThatThrownBy(() -> BetaPageParser.parse(body, QUOTED_AT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.BETA);
              assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE);
              assertThat(e.getCause()).hasMessageNotContaining("TOP-SECRET");
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not xml",
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments>",
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments></SegmentsResponse>",
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments/></SegmentsResponse><x/>"
      })
  void failsAPageThatIsNotWellFormed(String body) {
    assertThatThrownBy(() -> BetaPageParser.parse(body, QUOTED_AT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.BETA);
              assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE);
            });
  }

  @Test
  void readsThePageCountAndPointsToTheNextPageUntilTheLast() {
    ParsedPage first = BetaPageParser.parse(pageOf(1, 3), QUOTED_AT);
    ParsedPage last = BetaPageParser.parse(pageOf(3, 3), QUOTED_AT);

    assertThat(first.next()).contains("2");
    assertThat(first.announcedPages()).contains(3);
    assertThat(last.next()).isEmpty();
    assertThat(last.announcedPages()).contains(3);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<Other page=\"1\" totalPages=\"1\"><Segments/></Other>",
        "<SegmentsResponse totalPages=\"1\"><Segments/></SegmentsResponse>",
        "<SegmentsResponse page=\"1\"><Segments/></SegmentsResponse>",
        "<SegmentsResponse page=\"x\" totalPages=\"1\"><Segments/></SegmentsResponse>",
        "<SegmentsResponse page=\"0\" totalPages=\"1\"><Segments/></SegmentsResponse>",
        "<SegmentsResponse page=\"1\" totalPages=\"0\"><Segments/></SegmentsResponse>",
        "<SegmentsResponse page=\"1\" totalPages=\"99999999999\"><Segments/></SegmentsResponse>",
        "<SegmentsResponse page=\"1\" totalPages=\"1\"/>",
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments><Foo/></Segments></SegmentsResponse>",
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segment/></SegmentsResponse>"
      })
  void failsAPageThatIsNotBetasShape(String body) {
    assertThatThrownBy(() -> BetaPageParser.parse(body, QUOTED_AT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.BETA);
              assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE);
              assertThat(e.getCause()).isNotNull();
            });
  }

  @Test
  void ignoresAnElementItDoesNotKnow() {
    String segment =
        VALID_SEGMENT.replace("<PriceCents>", "<Seats><Row>1</Row><Row/></Seats><PriceCents>");

    ParsedPage page = BetaPageParser.parse(pageOf(1, 1, segment), QUOTED_AT);

    assertThat(page.legs()).extracting(Leg::flightNumber).containsExactly(3170);
    assertThat(page.discarded()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("segmentsOfTheWrongShape")
  void discardsASegmentOfTheWrongShapeAndKeepsItsNeighbours(String segment, String excerpt) {
    ParsedPage page =
        BetaPageParser.parse(pageOf(1, 1, VALID_SEGMENT, segment, VALID_SEGMENT), QUOTED_AT);

    assertThat(page.legs()).hasSize(2);
    assertThat(page.discarded()).containsExactly(new DiscardedRecord(DiscardReason.SHAPE, excerpt));
  }

  static Stream<Arguments> segmentsOfTheWrongShape() {
    return Stream.of(
        arguments(
            VALID_SEGMENT.replace("</Segment>", "<PriceCents>1</PriceCents></Segment>"),
            "carrier=IB flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
        arguments(
            VALID_SEGMENT.replace("3170", "<x>3170</x>"),
            "carrier=IB origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
        arguments(
            VALID_SEGMENT.replace(
                "<Carrier code=\"IB\"/>", "<Carrier code=\"IB\"/><Carrier code=\"BA\"/>"),
            "carrier=IB flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
        arguments(
            VALID_SEGMENT.replace("<Carrier code=\"IB\"/>", "<Carrier code=\"IB\"><x/></Carrier>"),
            "carrier=IB flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"));
  }

  private static String pageOf(int page, int totalPages, String... segments) {
    return "<SegmentsResponse page=\""
        + page
        + "\" totalPages=\""
        + totalPages
        + "\"><Segments>"
        + String.join("", segments)
        + "</Segments></SegmentsResponse>";
  }
}
