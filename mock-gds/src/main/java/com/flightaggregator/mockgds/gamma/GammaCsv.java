package com.flightaggregator.mockgds.gamma;

import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.PricedRecord;
import com.flightaggregator.mockgds.serving.RenderedLeg;

/** Writes a page of Gamma's inventory as CSV with a header row and {@code \r\n} line ends. */
final class GammaCsv {

  private static final String HEADER =
      "airline,flight_number,from,to,departs_at,arrives_at,price_eur";
  private static final String EOL = "\r\n";

  private GammaCsv() {}

  static String write(InventoryPage page) {
    var out = new StringBuilder(HEADER).append(EOL);
    for (PricedRecord priced : page.records()) {
      var leg = RenderedLeg.of(priced);
      out.append(leg.carrier())
          .append(',')
          .append(leg.flightNumber() == null ? "" : leg.flightNumber().toString())
          .append(',')
          .append(leg.origin())
          .append(',')
          .append(leg.destination())
          .append(',')
          .append(leg.departureTime())
          .append(',')
          .append(leg.arrivalTime())
          .append(',')
          .append(leg.price().toPlainString())
          .append(EOL);
    }
    return out.toString();
  }
}
