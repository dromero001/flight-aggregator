package com.flightaggregator.adapter.provider;

import static com.flightaggregator.adapter.provider.Fixtures.FLIGHT;
import static com.flightaggregator.adapter.provider.Fixtures.path;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BetaPageTest {

  private static final Instant QUOTED_AT = Instant.parse("2026-11-15T10:00:00Z");

  private static final String VALID_SEGMENT =
      "<Segment><Carrier code=\"IB\"/><FlightNumber>3170</FlightNumber>"
          + "<Departure airport=\"MAD\">2026-11-20T08:15:00+01:00</Departure>"
          + "<Arrival airport=\"LHR\">2026-11-20T09:30:00+00:00</Arrival>"
          + "<PriceCents>12990</PriceCents></Segment>";

  @Test
  void parsesAValidSegment() {
    ParsedPage page = parse(pageOf(1, 1, VALID_SEGMENT), QUOTED_AT, 1);

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
        parse(
            pageOf(
                1,
                1,
                VALID_SEGMENT,
                missingFlightNumber,
                badDate,
                negativePrice,
                unknownAirport,
                VALID_SEGMENT),
            QUOTED_AT,
            1);

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

    Throwable failure = catchThrowable(() -> parse(body, QUOTED_AT, 1));

    assertThat(failure).isInstanceOf(PageMalformed.class);
    // Neither the failure nor its cause, as a log line would print them, holds the file's content.
    StringWriter printed = new StringWriter();
    failure.printStackTrace(new PrintWriter(printed));
    assertThat(printed.toString()).doesNotContain("TOP-SECRET");
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
    assertThatThrownBy(() -> parse(body, QUOTED_AT, 1)).isInstanceOf(PageMalformed.class);
  }

  @Test
  void readsThePageCountAndPointsToTheNextPageUntilTheLast() {
    ParsedPage first = parse(pageOf(1, 3), QUOTED_AT, 1);
    ParsedPage last = parse(pageOf(3, 3), QUOTED_AT, 3);

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
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segment/></SegmentsResponse>"
      })
  void failsAPageThatIsNotBetasShape(String body) {
    assertThatThrownBy(() -> parse(body, QUOTED_AT, 1)).isInstanceOf(PageMalformed.class);
  }

  @Test
  void ignoresAnElementItDoesNotKnow() {
    String segment =
        VALID_SEGMENT.replace("<PriceCents>", "<Seats><Row>1</Row><Row/></Seats><PriceCents>");

    ParsedPage page = parse(pageOf(1, 1, segment), QUOTED_AT, 1);

    assertThat(page.legs()).extracting(Leg::flightNumber).containsExactly(3170);
    assertThat(page.discarded()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("segmentsOfTheWrongShape")
  void discardsASegmentOfTheWrongShapeAndKeepsItsNeighbours(String segment, String excerpt) {
    ParsedPage page = parse(pageOf(1, 1, VALID_SEGMENT, segment, VALID_SEGMENT), QUOTED_AT, 1);

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
            "flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
        arguments(
            VALID_SEGMENT.replace("<Carrier code=\"IB\"/>", "<Carrier/><Carrier code=\"BA\"/>"),
            "flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"),
        arguments(
            VALID_SEGMENT.replace("<Segment>", "<Segment>junk"),
            "carrier=IB flightNumber=3170 origin=MAD departureTime=2026-11-20T08:15:00+01:00 "
                + "destination=LHR arrivalTime=2026-11-20T09:30:00+00:00 price=12990"));
  }

  @Test
  void discardsAnItemThatIsNotASegmentAndKeepsItsNeighbours() {
    String body =
        "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments>"
            + VALID_SEGMENT
            + "<Foo><Bar/></Foo>"
            + VALID_SEGMENT
            + "</Segments></SegmentsResponse>";

    ParsedPage page = parse(body, QUOTED_AT, 1);

    assertThat(page.legs()).hasSize(2);
    assertThat(page.discarded())
        .containsExactly(new DiscardedRecord(DiscardReason.SHAPE, "element=Foo"));
  }

  @Test
  void ignoresProcessingInstructionsAndComments() {
    String fieldWithPi = VALID_SEGMENT.replace("3170", "31<?x y?>70<!-- c -->");
    String unknownWithPi = VALID_SEGMENT.replace("<PriceCents>", "<Note><?x?></Note><PriceCents>");

    ParsedPage page = parse(pageOf(1, 1, fieldWithPi, unknownWithPi), QUOTED_AT, 1);

    assertThat(page.legs()).extracting(Leg::flightNumber).containsExactly(3170, 3170);
    assertThat(page.discarded()).isEmpty();
  }

  /** Every item counts toward the page size, a segment that is discarded or not a segment too. */
  @Test
  void failsAPageWithMoreRecordsThanThePageSize() {
    String threeRecords = pageOf(1, 1, VALID_SEGMENT, "<Note/>", VALID_SEGMENT);

    assertThatThrownBy(
            () ->
                new BetaPage(ListingQuery.from(Optional.empty()), 1, 2)
                    .parse(new FetchedPage(threeRecords, Provider.BETA, QUOTED_AT)))
        .isInstanceOf(PageMalformed.class)
        .hasMessage("Asked for at most 2 records, got 3");
  }

  @Test
  void failsAPageThatIsNotTheRequestedOne() {
    assertThatThrownBy(() -> parse(pageOf(5, 5), QUOTED_AT, 2))
        .isInstanceOfSatisfying(
            PageMalformed.class,
            e -> {
              assertThat(e).hasMessageContaining("page 5").hasMessageContaining("2");
            });
  }

  @Test
  void quotesAndCutsAnElementNameInTheFailureMessage() {
    String name = "x".repeat(500);

    assertThatThrownBy(() -> parse("<" + name + " page=\"1\" totalPages=\"1\"/>", QUOTED_AT, 1))
        .isInstanceOfSatisfying(
            PageMalformed.class,
            e ->
                assertThat(e.getMessage())
                    .isEqualTo(
                        "Expected <SegmentsResponse>, not the element "
                            + ProviderText.quoted(name)));
  }

  @Test
  void neverFetchesAnExternalDtd() {
    WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    try {
      String body =
          "<!DOCTYPE SegmentsResponse SYSTEM \""
              + server.baseUrl()
              + "/evil.dtd\">"
              + pageOf(1, 1, VALID_SEGMENT);

      assertThatThrownBy(() -> parse(body, QUOTED_AT, 1)).isInstanceOf(PageMalformed.class);
      server.verify(0, getRequestedFor(urlEqualTo("/evil.dtd")));
    } finally {
      server.stop();
    }
  }

  @Test
  void parsesTheMocksEmptyPageBeyondTheLastAsTheEnd() {
    ParsedPage page =
        parse(
            "<SegmentsResponse page=\"3\" totalPages=\"1\"><Segments/></SegmentsResponse>",
            QUOTED_AT,
            3);

    assertThat(page.legs()).isEmpty();
    assertThat(page.discarded()).isEmpty();
    assertThat(page.next()).isEmpty();
  }

  /** The origin joins the listing's query (Task 1), after the page number and size. */
  @Test
  void requestsOnlyAnOriginsLegs() {
    PageRequest request =
        BetaPage.FORMAT.at(Optional.of(new Airport("MAD")), Optional.of("3"), 100);

    assertThat(path(request)).isEqualTo("/beta/segments?page=3&pageSize=100&origin=MAD");
  }

  /**
   * A quote is the listing with the flight's five key parts (Task 1), the departure a UTC instant
   * ending in {@code Z}, on the first page of one record: the most a quote may answer.
   */
  @Test
  void buildsTheQuotePath() {
    PageRequest request = BetaPage.FORMAT.quoting(FLIGHT);

    assertThat(path(request))
        .isEqualTo(
            "/beta/segments?page=1&pageSize=1&carrier=IB&flightNumber=3170&origin=MAD"
                + "&destination=LHR&departure=2026-11-20T07%3A15%3A00Z");
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

  /** Parses the Beta page of the requested number, at the production page size. */
  private static ParsedPage parse(String body, Instant quotedAt, int requestedPage) {
    return new BetaPage(ListingQuery.from(Optional.empty()), requestedPage, 1000)
        .parse(new FetchedPage(body, Provider.BETA, quotedAt));
  }
}
