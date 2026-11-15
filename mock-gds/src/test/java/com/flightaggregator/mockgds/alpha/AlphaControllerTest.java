package com.flightaggregator.mockgds.alpha;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.support.Fixtures;
import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@MockGdsTest
class AlphaControllerTest {

  @Autowired private TestRestTemplate http;
  @Autowired private Dataset dataset;

  @Test
  void servesTheFirstPageWithoutACursor() throws Exception {
    var response = get("/alpha/legs");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    var body = new ObjectMapper().readTree(response.getBody());
    assertThat(body.get("legs")).hasSize(500);
    assertThat(body.get("nextCursor").asString()).isEqualTo(AlphaCursor.encode(500));
    assertThat(response.getBody()).doesNotContain(ProviderKeys.ALPHA);
  }

  @Test
  void followsTheCursorToTheLastPage() throws Exception {
    var mapper = new ObjectMapper();
    var path = "/alpha/legs?limit=700";
    var served = 0;
    var pages = 0;
    JsonNode cursor;
    do {
      var body = mapper.readTree(get(path).getBody());
      served += body.get("legs").size();
      cursor = body.get("nextCursor");
      path = "/alpha/legs?limit=700&cursor=" + cursor.asString();
    } while (!cursor.isNull() && pages++ < 50);

    assertThat(served).isEqualTo(dataset.recordsFor(Provider.ALPHA).size());
  }

  @Test
  void rejectsAnUnreadableCursor() {
    assertThat(get("/alpha/legs?cursor=not%20base64!").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsALimitOutOfRange() {
    assertThat(get("/alpha/legs?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/alpha/legs?limit=1001").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsAnEmptyLimit() {
    assertThat(get("/alpha/legs?limit=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void servesOnlyTheOriginsLegs() throws Exception {
    var mapper = new ObjectMapper();
    var expected =
        dataset.recordsFor(Provider.ALPHA).stream()
            .filter(record -> record.leg().origin().equals("MAD"))
            .count();
    var origins = new ArrayList<String>();
    var path = "/alpha/legs?origin=MAD&limit=300";
    JsonNode cursor;
    do {
      var body = mapper.readTree(get(path).getBody());
      body.get("legs").forEach(leg -> origins.add(leg.get("origin").asString()));
      cursor = body.get("nextCursor");
      path = "/alpha/legs?origin=MAD&limit=300&cursor=" + cursor.asString();
    } while (!cursor.isNull() && origins.size() <= expected);

    assertThat(expected).isGreaterThan(0);
    assertThat(origins).hasSize((int) expected).containsOnly("MAD");
  }

  @Test
  void quotesOneFlightInItsFormat() throws Exception {
    var key = dataset.recordsFor(Provider.ALPHA).get(7).leg().key();

    var response = get("/alpha/legs?" + Fixtures.quoteQuery(key));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = new ObjectMapper().readTree(response.getBody());
    assertThat(body.get("legs")).hasSize(1);
    var leg = body.get("legs").get(0);
    assertThat(leg.get("carrier").asString()).isEqualTo(key.airline());
    assertThat(leg.get("flightNumber").asInt()).isEqualTo(key.flightNumber());
    assertThat(leg.get("origin").asString()).isEqualTo(key.origin());
    assertThat(leg.get("destination").asString()).isEqualTo(key.destination());
    assertThat(OffsetDateTime.parse(leg.get("departureTime").asString()).toInstant())
        .isEqualTo(key.departure());
    assertThat(new BigDecimal(leg.get("price").asString())).isPositive();
    assertThat(body.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void quotesNothingInItsFormat() {
    var sold = dataset.recordsFor(Provider.ALPHA).get(7).leg().key();
    var unsold =
        new LegKey(
            sold.airline(),
            sold.flightNumber(),
            sold.origin(),
            sold.destination(),
            sold.departure().plus(Duration.ofDays(400)));

    var response = get("/alpha/legs?" + Fixtures.quoteQuery(unsold));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("{\"legs\":[],\"nextCursor\":null}");
  }

  @Test
  void refusesAPartialFlightKey() {
    assertThat(get("/alpha/legs?carrier=IB&flightNumber=3170").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void refusesAnOriginThatIsNotAnIataCode() {
    for (var query :
        List.of("origin=", "origin=mad", "origin=MA", "origin=MADX", "origin=MAD&origin=BCN")) {
      assertThat(get("/alpha/legs?" + query).getStatusCode())
          .as(query)
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void refusesAFlightKeyWithMalformedParts() {
    var key = dataset.recordsFor(Provider.ALPHA).get(7).leg().key();
    var query = Fixtures.quoteQuery(key);

    for (var bad :
        List.of(
            query.replace("destination=" + key.destination(), "destination=lhr"),
            query.replace("carrier=" + key.airline(), "carrier=ib"),
            query.replace("carrier=" + key.airline(), "carrier=IBE"),
            query.replace("flightNumber=" + key.flightNumber(), "flightNumber=abc"))) {
      assertThat(get("/alpha/legs?" + bad).getStatusCode())
          .as(bad)
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void refusesADepartureThatIsNotAnInstant() {
    var key = dataset.recordsFor(Provider.ALPHA).get(7).leg().key();
    var query = Fixtures.quoteQuery(key).replace("departure=" + key.departure(), "departure=");

    for (var departure : List.of("x", "2026-11-20T08:15:00%2B01:00", "2026-11-20")) {
      assertThat(get("/alpha/legs?" + query + departure).getStatusCode())
          .as(departure)
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  private ResponseEntity<String> get(String path) {
    return ProviderKeys.get(http, path, ProviderKeys.ALPHA);
  }
}
