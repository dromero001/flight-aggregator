package com.flightaggregator.adapter.persistence;

import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Money;
import java.time.OffsetDateTime;
import tools.jackson.core.json.JsonFactory;

/**
 * An itinerary as the cells of its {@code COPY} row. Its id and price are computed once, when the
 * row is made (R11).
 */
final class ItineraryRow {

  /** The columns of the itinerary tables, in the order of {@link #cells}. */
  static final String COLUMNS =
      "id, departure, departure_offset, arrival, arrival_offset, origin, destination,"
          + " duration_seconds, stops, single_carrier, price, quoted_at, legs";

  private final Itinerary itinerary;
  private final ItineraryId id;
  private final Money price;

  private ItineraryRow(Itinerary itinerary, ItineraryId id, Money price) {
    this.itinerary = itinerary;
    this.id = id;
    this.price = price;
  }

  static ItineraryRow of(Itinerary itinerary) {
    return new ItineraryRow(itinerary, itinerary.id(), itinerary.price());
  }

  /** The cells in the order of {@link #COLUMNS}; a null cell is a SQL null. */
  String[] cells(JsonFactory json) {
    OffsetDateTime departure = itinerary.departure();
    OffsetDateTime arrival = itinerary.arrival();
    return new String[] {
      id.value().toString(),
      ProviderTime.instantCell(departure),
      ProviderTime.offsetCell(departure),
      ProviderTime.instantCell(arrival),
      ProviderTime.offsetCell(arrival),
      itinerary.origin().code(),
      itinerary.destination().code(),
      Long.toString(itinerary.duration().toSeconds()),
      Integer.toString(itinerary.legs().size() - 1),
      itinerary.singleCarrier().map(Carrier::code).orElse(null),
      price.amount().toPlainString(),
      itinerary.quotedAt().toString(),
      LegsJson.write(itinerary.legs(), json)
    };
  }
}
