package com.flightaggregator.mockgds.alpha;

import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.PricedRecord;
import com.flightaggregator.mockgds.serving.RenderedLeg;
import java.io.StringWriter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

/** Writes a page of Alpha's inventory as its flat JSON list. */
final class AlphaJson {

  private static final JsonFactory FACTORY = new JsonFactory();

  private AlphaJson() {}

  static String write(InventoryPage page) {
    var out = new StringWriter();
    try (JsonGenerator json = FACTORY.createGenerator(ObjectWriteContext.empty(), out)) {
      json.writeStartObject();
      json.writeName("legs");
      json.writeStartArray();
      for (PricedRecord priced : page.records()) {
        writeLeg(json, priced);
      }
      json.writeEndArray();
      json.writeName("nextCursor");
      if (page.isLast()) {
        json.writeNull();
      } else {
        json.writeString(AlphaCursor.encode(page.offset() + page.records().size()));
      }
      json.writeEndObject();
    }
    return out.toString();
  }

  private static void writeLeg(JsonGenerator json, PricedRecord priced) {
    var leg = RenderedLeg.of(priced);
    json.writeStartObject();
    json.writeStringProperty("carrier", leg.carrier());
    if (leg.flightNumber() != null) {
      json.writeNumberProperty("flightNumber", leg.flightNumber());
    }
    json.writeStringProperty("origin", leg.origin());
    json.writeStringProperty("destination", leg.destination());
    json.writeStringProperty("departureTime", leg.departureTime());
    json.writeStringProperty("arrivalTime", leg.arrivalTime());
    json.writeStringProperty("price", leg.price().toPlainString());
    json.writeEndObject();
  }
}
