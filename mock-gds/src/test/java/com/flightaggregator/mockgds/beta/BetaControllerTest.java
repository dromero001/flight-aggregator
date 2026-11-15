package com.flightaggregator.mockgds.beta;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.support.Fixtures;
import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import com.flightaggregator.mockgds.support.Xml;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.w3c.dom.Element;

@MockGdsTest
class BetaControllerTest {

  @Autowired private TestRestTemplate http;
  @Autowired private Dataset dataset;

  @Test
  void servesPageOneByDefault() throws Exception {
    var response = get("/beta/segments");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_XML))
        .isTrue();
    var root = Xml.parse(response.getBody());
    assertThat(root.getAttribute("page")).isEqualTo("1");
    assertThat(root.getElementsByTagName("Segment").getLength()).isEqualTo(500);
    assertThat(response.getBody()).doesNotContain(ProviderKeys.BETA);
  }

  private ResponseEntity<String> get(String path) {
    return ProviderKeys.get(http, path, ProviderKeys.BETA);
  }

  @Test
  void pagesToTotalPages() throws Exception {
    var total = dataset.recordsFor(Provider.BETA).size();
    var first = Xml.parse(get("/beta/segments?pageSize=300").getBody());
    var totalPages = Integer.parseInt(first.getAttribute("totalPages"));

    var served = 0;
    for (var page = 1; page <= totalPages; page++) {
      var root = Xml.parse(get("/beta/segments?pageSize=300&page=" + page).getBody());
      assertThat(root.getAttribute("page")).isEqualTo(Integer.toString(page));
      served += root.getElementsByTagName("Segment").getLength();
    }

    assertThat(totalPages).isEqualTo((total + 299) / 300);
    assertThat(served).isEqualTo(total);
  }

  @Test
  void servesAnEmptyPageBeyondTheEnd() {
    var body = get("/beta/segments?pageSize=1000&page=1000000").getBody();

    assertThat(body)
        .matches(
            "<SegmentsResponse page=\"1000000\" totalPages=\"\\d+\"><Segments/></SegmentsResponse>");
  }

  @Test
  void servesAnEmptyPageForAnEnormousPageNumber() {
    var body = get("/beta/segments?pageSize=1000&page=2147483647").getBody();

    assertThat(body).contains("<Segments/>");
  }

  @Test
  void rejectsPageZeroAndAPageSizeOutOfRange() {
    assertThat(get("/beta/segments?page=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?page=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?page=2147483648").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?pageSize=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?pageSize=1001").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?pageSize=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void servesOnlyTheOriginsLegs() throws Exception {
    var expected =
        dataset.recordsFor(Provider.BETA).stream()
            .filter(record -> record.leg().origin().equals("MAD"))
            .count();
    var first = Xml.parse(get("/beta/segments?origin=MAD&pageSize=100").getBody());
    var totalPages = Integer.parseInt(first.getAttribute("totalPages"));

    var airports = new ArrayList<String>();
    for (var page = 1; page <= totalPages; page++) {
      var root = Xml.parse(get("/beta/segments?origin=MAD&pageSize=100&page=" + page).getBody());
      var departures = root.getElementsByTagName("Departure");
      for (var i = 0; i < departures.getLength(); i++) {
        airports.add(((Element) departures.item(i)).getAttribute("airport"));
      }
    }

    assertThat(expected).isGreaterThan(0);
    assertThat(airports).hasSize((int) expected).containsOnly("MAD");
  }

  @Test
  void quotesOneFlightInItsFormat() throws Exception {
    var key = dataset.recordsFor(Provider.BETA).get(7).leg().key();

    var response = get("/beta/segments?" + Fixtures.quoteQuery(key));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var root = Xml.parse(response.getBody());
    assertThat(root.getAttribute("totalPages")).isEqualTo("1");
    assertThat(root.getElementsByTagName("Segment").getLength()).isEqualTo(1);
    var segment = (Element) root.getElementsByTagName("Segment").item(0);
    assertThat(((Element) segment.getElementsByTagName("Carrier").item(0)).getAttribute("code"))
        .isEqualTo(key.airline());
    assertThat(segment.getElementsByTagName("FlightNumber").item(0).getTextContent())
        .isEqualTo(Integer.toString(key.flightNumber()));
    var departure = (Element) segment.getElementsByTagName("Departure").item(0);
    assertThat(departure.getAttribute("airport")).isEqualTo(key.origin());
    assertThat(OffsetDateTime.parse(departure.getTextContent()).toInstant())
        .isEqualTo(key.departure());
    var arrival = (Element) segment.getElementsByTagName("Arrival").item(0);
    assertThat(arrival.getAttribute("airport")).isEqualTo(key.destination());
    assertThat(
            Integer.parseInt(segment.getElementsByTagName("PriceCents").item(0).getTextContent()))
        .isPositive();
  }

  @Test
  void quotesNothingInItsFormat() {
    var sold = dataset.recordsFor(Provider.BETA).get(7).leg().key();
    var unsold =
        new LegKey(
            sold.airline(),
            sold.flightNumber(),
            sold.origin(),
            sold.destination(),
            sold.departure().plus(Duration.ofDays(400)));

    var response = get("/beta/segments?" + Fixtures.quoteQuery(unsold));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .isEqualTo("<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments/></SegmentsResponse>");
  }

  @Test
  void refusesAPartialFlightKey() {
    assertThat(get("/beta/segments?carrier=IB&flightNumber=3170").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Nested
  class WhenTheInventoryIsEmpty {

    @TestBean private ProviderInventory inventory;

    static ProviderInventory inventory() {
      return Fixtures.inventoryOf(List.of());
    }

    /** A client reading pages 1 to totalPages still reads page 1, and learns it is the last. */
    @Test
    void reportsOnePage() throws Exception {
      var root = Xml.parse(get("/beta/segments").getBody());

      assertThat(root.getAttribute("totalPages")).isEqualTo("1");
      assertThat(root.getElementsByTagName("Segment").getLength()).isZero();
    }
  }
}
