package com.flightaggregator.mockgds;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import com.flightaggregator.mockgds.support.Xml;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Downloads each provider over HTTP to its own end signal and checks that every record of the
 * dataset arrives exactly once, in order, malformed ones included. The readers below are minimal
 * and test-only: the real parsers belong to the adapters.
 */
@MockGdsTest
class ProvidersEndToEndTest {

  private static final int PAGE = 700;

  /** The identity of a record as a client reads it; a defect leaves its own cell broken. */
  private record Row(
      String airline, String flightNumber, String origin, String destination, String departure) {}

  @Autowired private TestRestTemplate http;
  @Autowired private Dataset dataset;

  @Test
  void downloadsEveryRecordOfEveryProviderExactlyOnce() throws Exception {
    var downloaded =
        Map.of(
            Provider.ALPHA, downloadAlpha(),
            Provider.BETA, downloadBeta(),
            Provider.GAMMA, downloadGamma());

    for (var provider : Provider.values()) {
      assertThat(dataset.recordsFor(provider)).as(provider.name()).hasSizeGreaterThan(PAGE);
      assertThat(downloaded.get(provider))
          .as(provider.name())
          .containsExactlyElementsOf(
              dataset.recordsFor(provider).stream().map(this::expected).toList());
    }
  }

  private Row expected(ProviderRecord record) {
    var leg = record.leg();
    var defect = record instanceof MalformedLeg malformed ? malformed.defect() : null;
    return new Row(
        leg.airline(),
        defect == Defect.MISSING_FIELD ? "" : Integer.toString(leg.flightNumber()),
        leg.origin(),
        defect == Defect.UNKNOWN_AIRPORT ? Defect.UNKNOWN_AIRPORT_CODE : leg.destination(),
        defect == Defect.BAD_DATE
            ? Defect.INVALID_DATE_TEXT
            : leg.departure().toInstant().toString());
  }

  /** The departure as an instant when it is a valid time, so offsets do not matter. */
  private static String departure(String text) {
    try {
      return OffsetDateTime.parse(text).toInstant().toString();
    } catch (DateTimeParseException e) {
      return text;
    }
  }

  private List<Row> downloadAlpha() throws Exception {
    var mapper = new ObjectMapper();
    var rows = new ArrayList<Row>();
    var path = "/alpha/legs?limit=" + PAGE;
    while (path != null) {
      JsonNode body = mapper.readTree(ProviderKeys.get(http, path, ProviderKeys.ALPHA).getBody());
      for (JsonNode leg : body.get("legs")) {
        rows.add(
            new Row(
                leg.get("carrier").asString(),
                leg.has("flightNumber") ? leg.get("flightNumber").asString() : "",
                leg.get("origin").asString(),
                leg.get("destination").asString(),
                departure(leg.get("departureTime").asString())));
      }
      var cursor = body.get("nextCursor");
      path = cursor.isNull() ? null : "/alpha/legs?limit=" + PAGE + "&cursor=" + cursor.asString();
    }
    return rows;
  }

  private List<Row> downloadBeta() throws Exception {
    var rows = new ArrayList<Row>();
    var totalPages = 1;
    for (var page = 1; page <= totalPages; page++) {
      var root =
          Xml.parse(
              ProviderKeys.get(
                      http, "/beta/segments?pageSize=" + PAGE + "&page=" + page, ProviderKeys.BETA)
                  .getBody());
      totalPages = Integer.parseInt(root.getAttribute("totalPages"));
      var segments = root.getElementsByTagName("Segment");
      for (var i = 0; i < segments.getLength(); i++) {
        var segment = (Element) segments.item(i);
        var departure = (Element) segment.getElementsByTagName("Departure").item(0);
        var arrival = (Element) segment.getElementsByTagName("Arrival").item(0);
        var number = segment.getElementsByTagName("FlightNumber");
        rows.add(
            new Row(
                ((Element) segment.getElementsByTagName("Carrier").item(0)).getAttribute("code"),
                number.getLength() == 0 ? "" : number.item(0).getTextContent(),
                departure.getAttribute("airport"),
                arrival.getAttribute("airport"),
                departure(departure.getTextContent())));
      }
    }
    return rows;
  }

  private List<Row> downloadGamma() {
    var rows = new ArrayList<Row>();
    int served;
    var offset = 0;
    do {
      var path = "/gamma/legs.csv?limit=" + PAGE + "&offset=" + offset;
      var lines = ProviderKeys.get(http, path, ProviderKeys.GAMMA).getBody().split("\r\n");
      IntStream.range(1, lines.length)
          .mapToObj(i -> lines[i].split(",", -1))
          .forEach(
              cells ->
                  rows.add(new Row(cells[0], cells[1], cells[2], cells[3], departure(cells[4]))));
      served = lines.length - 1;
      offset += served;
    } while (served == PAGE);
    return rows;
  }
}
