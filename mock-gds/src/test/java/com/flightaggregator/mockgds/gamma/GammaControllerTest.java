package com.flightaggregator.mockgds.gamma;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.support.Fixtures;
import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.convention.TestBean;

@MockGdsTest
class GammaControllerTest {

  private static final String HEADER =
      "airline,flight_number,from,to,departs_at,arrives_at,price_eur";

  @Autowired private TestRestTemplate http;
  @Autowired private Dataset dataset;

  @Test
  void servesFromOffsetZeroByDefault() {
    var response = get("/gamma/legs.csv");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            response.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/csv")))
        .isTrue();
    assertThat(response.getHeaders().getContentType().getCharset().name()).isEqualTo("UTF-8");
    var lines = response.getBody().split("\r\n");
    assertThat(lines[0]).isEqualTo(HEADER);
    assertThat(lines).hasSize(501);
    assertThat(response.getBody()).doesNotContain(ProviderKeys.GAMMA);
  }

  @Test
  void endsWithAShortPage() {
    var total = dataset.recordsFor(Provider.GAMMA).size();

    var lines = get("/gamma/legs.csv?limit=500&offset=" + (total - 3)).getBody().split("\r\n");

    assertThat(lines).hasSize(4);
    assertThat(lines[0]).isEqualTo(HEADER);
  }

  private ResponseEntity<String> get(String path) {
    return ProviderKeys.get(http, path, ProviderKeys.GAMMA);
  }

  @Test
  void rejectsANegativeOffsetAndALimitOutOfRange() {
    for (var query :
        List.of("offset=-1", "offset=", "offset=abc", "limit=0", "limit=1001", "limit=")) {
      assertThat(get("/gamma/legs.csv?" + query).getStatusCode())
          .as(query)
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void servesOnlyTheOriginsLegs() {
    var expected =
        dataset.recordsFor(Provider.GAMMA).stream()
            .filter(record -> record.leg().origin().equals("MAD"))
            .count();

    var served = new ArrayList<String>();
    for (var offset = 0; offset <= expected; offset += 200) {
      var lines =
          get("/gamma/legs.csv?origin=MAD&limit=200&offset=" + offset).getBody().split("\r\n");
      for (var line : List.of(lines).subList(1, lines.length)) {
        served.add(line.split(",")[2]);
      }
    }

    assertThat(expected).isGreaterThan(0);
    assertThat(served).hasSize((int) expected).containsOnly("MAD");
  }

  @Test
  void quotesOneFlightInItsFormat() {
    var key = dataset.recordsFor(Provider.GAMMA).get(7).leg().key();

    var lines = get("/gamma/legs.csv?" + Fixtures.quoteQuery(key)).getBody().split("\r\n");

    assertThat(lines).hasSize(2);
    assertThat(lines[0]).isEqualTo(HEADER);
    var cells = lines[1].split(",");
    assertThat(cells[0]).isEqualTo(key.airline());
    assertThat(cells[1]).isEqualTo(Integer.toString(key.flightNumber()));
    assertThat(cells[2]).isEqualTo(key.origin());
    assertThat(cells[3]).isEqualTo(key.destination());
    assertThat(OffsetDateTime.parse(cells[4]).toInstant()).isEqualTo(key.departure());
    assertThat(new BigDecimal(cells[6])).isPositive();
  }

  @Test
  void quotesNothingInItsFormat() {
    var sold = dataset.recordsFor(Provider.GAMMA).get(7).leg().key();
    var unsold =
        new LegKey(
            sold.airline(),
            sold.flightNumber(),
            sold.origin(),
            sold.destination(),
            sold.departure().plus(Duration.ofDays(400)));

    var response = get("/gamma/legs.csv?" + Fixtures.quoteQuery(unsold));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().split("\r\n")).containsExactly(HEADER);
  }

  @Test
  void refusesADepartureThatIsNotAnInstant() {
    var key = dataset.recordsFor(Provider.GAMMA).get(7).leg().key();
    var query = Fixtures.quoteQuery(key).replace("departure=" + key.departure(), "departure=");

    for (var departure : List.of("x", "2026-11-20T08:15:00%2B01:00", "2026-11-20")) {
      assertThat(get("/gamma/legs.csv?" + query + departure).getStatusCode())
          .as(departure)
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void refusesAnOriginThatIsNotAnIataCode() {
    assertThat(get("/gamma/legs.csv?origin=mad").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void refusesAPartialFlightKey() {
    assertThat(get("/gamma/legs.csv?carrier=IB&flightNumber=3170").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Nested
  class WhenTheTotalIsAMultipleOfTheLimit {

    @TestBean private ProviderInventory inventory;

    /** Four Gamma records, so that a limit of two divides the total. */
    static ProviderInventory inventory() {
      var first = OffsetDateTime.parse("2026-11-20T08:15:00+01:00");
      var records =
          IntStream.range(0, 4)
              .<ProviderRecord>mapToObj(
                  i ->
                      new ValidLeg(
                          new Leg(
                              "IB",
                              3170 + i,
                              "MAD",
                              "LHR",
                              first.plusDays(i),
                              first.plusDays(i).plusHours(2),
                              new BigDecimal("129.90"),
                              Provider.GAMMA)))
              .toList();
      return Fixtures.inventoryOf(records);
    }

    @Test
    void endsWithAHeaderOnlyPageWhenTheTotalIsAMultipleOfTheLimit() {
      var last = get("/gamma/legs.csv?limit=2&offset=2").getBody().split("\r\n");
      var after = get("/gamma/legs.csv?limit=2&offset=4").getBody().split("\r\n");

      assertThat(last).hasSize(3);
      assertThat(after).containsExactly(HEADER);
    }
  }
}
