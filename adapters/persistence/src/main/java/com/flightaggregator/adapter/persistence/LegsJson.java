package com.flightaggregator.adapter.persistence;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

/**
 * The legs of an itinerary as the JSON array stored in its {@code legs} column, in flight order,
 * each leg's times at its provider's offset.
 */
final class LegsJson {

  /**
   * A leg's local time as ISO-8601 with its offset, written {@code +00:00} rather than {@code Z} at
   * UTC, so every leg reads the same way.
   */
  private static final DateTimeFormatter LOCAL_TIME =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .appendOffset("+HH:MM:ss", "+00:00")
          .toFormatter(Locale.ROOT);

  private LegsJson() {}

  static String write(List<Leg> legs, JsonFactory json) {
    StringWriter text = new StringWriter();
    try (JsonGenerator generator = json.createGenerator(ObjectWriteContext.empty(), text)) {
      generator.writeStartArray();
      for (Leg leg : legs) {
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

  /**
   * The legs of a document {@link #write} produced. Any other shape, a missing or unknown property
   * or a value of the wrong type, is an {@link IllegalStateException}.
   */
  static List<Leg> read(String document, JsonFactory json) {
    try (JsonParser parser = json.createParser(ObjectReadContext.empty(), document)) {
      expect(parser.nextToken(), JsonToken.START_ARRAY);
      List<Leg> legs = new ArrayList<>();
      while (parser.nextToken() == JsonToken.START_OBJECT) {
        legs.add(leg(parser));
      }
      expect(parser.currentToken(), JsonToken.END_ARRAY);
      if (parser.nextToken() != null) {
        throw notLegs("trailing content");
      }
      return List.copyOf(legs);
    } catch (JacksonException | IllegalArgumentException | DateTimeException e) {
      throw notLegs(e);
    }
  }

  /** One leg, its properties in the order {@link #write} gives them. */
  private static Leg leg(JsonParser parser) {
    Carrier carrier = new Carrier(string(parser, "carrier"));
    int flightNumber = number(parser, "flightNumber");
    Airport origin = new Airport(string(parser, "origin"));
    Airport destination = new Airport(string(parser, "destination"));
    OffsetDateTime departure = OffsetDateTime.parse(string(parser, "departure"));
    OffsetDateTime arrival = OffsetDateTime.parse(string(parser, "arrival"));
    Money price = new Money(new BigDecimal(string(parser, "price")));
    Provider provider = Provider.valueOf(string(parser, "provider"));
    Instant quotedAt = Instant.parse(string(parser, "quotedAt"));
    expect(parser.nextToken(), JsonToken.END_OBJECT);
    return new Leg(
        carrier, flightNumber, origin, destination, departure, arrival, price, provider, quotedAt);
  }

  private static String string(JsonParser parser, String name) {
    nextProperty(parser, name);
    expect(parser.nextToken(), JsonToken.VALUE_STRING);
    return parser.getString();
  }

  private static int number(JsonParser parser, String name) {
    nextProperty(parser, name);
    expect(parser.nextToken(), JsonToken.VALUE_NUMBER_INT);
    return parser.getIntValue();
  }

  private static void nextProperty(JsonParser parser, String name) {
    expect(parser.nextToken(), JsonToken.PROPERTY_NAME);
    if (!name.equals(parser.currentName())) {
      throw notLegs("expected property " + name + " but found " + parser.currentName());
    }
  }

  private static void expect(JsonToken actual, JsonToken expected) {
    if (actual != expected) {
      throw notLegs("expected " + expected + " but found " + actual);
    }
  }

  private static IllegalStateException notLegs(String why) {
    return new IllegalStateException("the legs document is not a list of legs: " + why);
  }

  private static IllegalStateException notLegs(Exception cause) {
    return new IllegalStateException("the legs document is not a list of legs", cause);
  }
}
