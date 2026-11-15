package com.flightaggregator.adapter.persistence;

import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
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

  /**
   * A leg's local time as ISO-8601 with its offset, written {@code +00:00} rather than {@code Z} at
   * UTC, so every leg reads the same way.
   */
  private static final DateTimeFormatter LOCAL_TIME =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .appendOffset("+HH:MM:ss", "+00:00")
          .toFormatter(Locale.ROOT);

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
      departure.toInstant().toString(),
      Integer.toString(departure.getOffset().getTotalSeconds()),
      arrival.toInstant().toString(),
      Integer.toString(arrival.getOffset().getTotalSeconds()),
      itinerary.origin().code(),
      itinerary.destination().code(),
      Long.toString(itinerary.duration().toSeconds()),
      Integer.toString(itinerary.legs().size() - 1),
      itinerary.singleCarrier().map(Carrier::code).orElse(null),
      price.amount().toPlainString(),
      itinerary.quotedAt().toString(),
      legs(json)
    };
  }

  /** The legs as a JSON array, in flight order, each at its provider's offset. */
  private String legs(JsonFactory json) {
    StringWriter text = new StringWriter();
    try (JsonGenerator generator = json.createGenerator(ObjectWriteContext.empty(), text)) {
      generator.writeStartArray();
      for (Leg leg : itinerary.legs()) {
        generator.writeStartObject();
        generator.writeStringProperty("carrier", leg.carrier().code());
        generator.writeNumberProperty("flightNumber", leg.flightNumber());
        generator.writeStringProperty("origin", leg.origin().code());
        generator.writeStringProperty("destination", leg.destination().code());
        generator.writeStringProperty("departure", LOCAL_TIME.format(leg.departure()));
        generator.writeStringProperty("arrival", LOCAL_TIME.format(leg.arrival()));
        generator.writeStringProperty("price", leg.price().amount().toPlainString());
        generator.writeStringProperty("provider", leg.provider().name());
        generator.writeStringProperty("quotedAt", leg.quotedAt().toString());
        generator.writeEndObject();
      }
      generator.writeEndArray();
    }
    return text.toString();
  }
}
