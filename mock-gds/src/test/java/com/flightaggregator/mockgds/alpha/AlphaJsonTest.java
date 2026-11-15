package com.flightaggregator.mockgds.alpha;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.PricedRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AlphaJsonTest {

  @Test
  void writesAValidLeg() {
    var page = new InventoryPage(List.of(priced(validLeg())), 0, 1);

    var json = AlphaJson.write(page);

    assertThat(json)
        .isEqualTo(
            "{\"legs\":[{\"carrier\":\"IB\",\"flightNumber\":3170,\"origin\":\"MAD\","
                + "\"destination\":\"LHR\",\"departureTime\":\"2026-11-20T08:15:00+01:00\","
                + "\"arrivalTime\":\"2026-11-20T09:30:00+00:00\",\"price\":\"129.90\"}],"
                + "\"nextCursor\":null}");
  }

  @Test
  void writesEachDefectInItsOwnRecord() throws Exception {
    var leg = validLeg();
    var records = new ArrayList<PricedRecord>();
    records.add(priced(leg));
    for (var defect : Defect.values()) {
      records.add(new PricedRecord(new MalformedLeg(leg, defect), leg.priceEur()));
    }

    var json = AlphaJson.write(new InventoryPage(records, 0, 5));

    var legs = new ObjectMapper().readTree(json).get("legs");
    assertThat(legs).hasSize(5);
    var valid = legs.get(0);
    assertThat(valid.toString()).contains("\"flightNumber\":3170");
    var missing = legs.get(1);
    assertThat(missing.has("flightNumber")).isFalse();
    assertThat(missing.get("departureTime")).isEqualTo(valid.get("departureTime"));
    var badDate = legs.get(2);
    assertThat(badDate.get("departureTime").asString()).isEqualTo("2026-02-30T25:61:00+01:00");
    assertThat(without(badDate, "departureTime")).isEqualTo(without(valid, "departureTime"));
    var negative = legs.get(3);
    assertThat(negative.get("price").asString()).isEqualTo("-129.90");
    assertThat(without(negative, "price")).isEqualTo(without(valid, "price"));
    var unknown = legs.get(4);
    assertThat(unknown.get("destination").asString()).isEqualTo("ZZZ");
    assertThat(without(unknown, "destination")).isEqualTo(without(valid, "destination"));
  }

  @Test
  void endsWithANullCursor() throws Exception {
    var page = new InventoryPage(List.of(priced(validLeg())), 4, 5);

    var cursor = new ObjectMapper().readTree(AlphaJson.write(page)).get("nextCursor");

    assertThat(cursor.isNull()).isTrue();
  }

  @Test
  void pointsToTheNextOffsetUnlessLast() throws Exception {
    var page = new InventoryPage(List.of(priced(validLeg())), 2, 5);

    var cursor = new ObjectMapper().readTree(AlphaJson.write(page)).get("nextCursor");

    assertThat(cursor.asString()).isEqualTo(AlphaCursor.encode(3));
  }

  private static JsonNode without(JsonNode leg, String field) {
    var copy = leg.deepCopy();
    ((ObjectNode) copy).remove(field);
    return copy;
  }

  private static Leg validLeg() {
    return new Leg(
        "IB",
        3170,
        "MAD",
        "LHR",
        OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
        OffsetDateTime.parse("2026-11-20T09:30:00Z"),
        new BigDecimal("129.90"),
        Provider.ALPHA);
  }

  private static PricedRecord priced(Leg leg) {
    return new PricedRecord(new ValidLeg(leg), leg.priceEur());
  }
}
