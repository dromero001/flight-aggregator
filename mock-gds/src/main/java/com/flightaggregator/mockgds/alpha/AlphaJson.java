package com.flightaggregator.mockgds.alpha;

import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.IsoTimes;
import com.flightaggregator.mockgds.serving.PricedRecord;
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
    var leg = priced.record().leg();
    var defect = priced.record() instanceof MalformedLeg malformed ? malformed.defect() : null;
    var price = defect == Defect.NEGATIVE_PRICE ? priced.price().negate() : priced.price();
    json.writeStartObject();
    json.writeStringProperty("carrier", leg.airline());
    if (defect != Defect.MISSING_FIELD) {
      json.writeNumberProperty("flightNumber", leg.flightNumber());
    }
    json.writeStringProperty("origin", leg.origin());
    json.writeStringProperty(
        "destination",
        defect == Defect.UNKNOWN_AIRPORT ? Defect.UNKNOWN_AIRPORT_CODE : leg.destination());
    json.writeStringProperty(
        "departureTime",
        defect == Defect.BAD_DATE ? Defect.INVALID_DATE_TEXT : IsoTimes.format(leg.departure()));
    json.writeStringProperty("arrivalTime", IsoTimes.format(leg.arrival()));
    json.writeStringProperty("price", price.toPlainString());
    json.writeEndObject();
  }
}
