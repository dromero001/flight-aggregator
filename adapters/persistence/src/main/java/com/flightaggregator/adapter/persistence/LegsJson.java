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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

  private static final String CARRIER = "carrier";
  private static final String FLIGHT_NUMBER = "flightNumber";
  private static final String ORIGIN = "origin";
  private static final String DESTINATION = "destination";
  private static final String DEPARTURE = "departure";
  private static final String ARRIVAL = "arrival";
  private static final String PRICE = "price";
  private static final String PROVIDER = "provider";
  private static final String QUOTED_AT = "quotedAt";

  /** The properties of a leg, one each, written and read by the same names. */
  private static final Set<String> PROPERTIES =
      Set.of(
          CARRIER,
          FLIGHT_NUMBER,
          ORIGIN,
          DESTINATION,
          DEPARTURE,
          ARRIVAL,
          PRICE,
          PROVIDER,
          QUOTED_AT);

  private LegsJson() {}

  static String write(List<Leg> legs, JsonFactory json) {
    StringWriter text = new StringWriter();
    try (JsonGenerator generator = json.createGenerator(ObjectWriteContext.empty(), text)) {
      generator.writeStartArray();
      for (Leg leg : legs) {
        generator.writeStartObject();
        generator.writeStringProperty(CARRIER, leg.carrier().code());
        generator.writeNumberProperty(FLIGHT_NUMBER, leg.flightNumber());
        generator.writeStringProperty(ORIGIN, leg.origin().code());
        generator.writeStringProperty(DESTINATION, leg.destination().code());
        generator.writeStringProperty(DEPARTURE, LOCAL_TIME.format(leg.departure()));
        generator.writeStringProperty(ARRIVAL, LOCAL_TIME.format(leg.arrival()));
        generator.writeStringProperty(PRICE, leg.price().amount().toPlainString());
        generator.writeStringProperty(PROVIDER, leg.provider().name());
        generator.writeStringProperty(QUOTED_AT, leg.quotedAt().toString());
        generator.writeEndObject();
      }
      generator.writeEndArray();
    }
    return text.toString();
  }

  /**
   * The legs of a document {@link #write} produced, its properties in any order (a {@code jsonb}
   * column does not keep it). A leg with a missing, unknown or duplicate property, a value of the
   * wrong type or one that does not parse, or any content after the array, is an {@link
   * IllegalStateException}.
   */
  static List<Leg> read(String document, JsonFactory json) {
    try (JsonParser parser = json.createParser(ObjectReadContext.empty(), document)) {
      expect(parser.nextToken(), JsonToken.START_ARRAY);
      List<Leg> legs = new ArrayList<>();
      while (parser.nextToken() == JsonToken.START_OBJECT) {
        legs.add(leg(properties(parser)));
      }
      expect(parser.currentToken(), JsonToken.END_ARRAY);
      expect(parser.nextToken(), null);
      return List.copyOf(legs);
    } catch (JacksonException | IllegalArgumentException | DateTimeException e) {
      throw notLegs(e);
    }
  }

  private static Map<String, String> properties(JsonParser parser) {
    Map<String, String> properties = new HashMap<>();
    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = parser.currentName();
      expect(
          parser.nextToken(),
          name.equals(FLIGHT_NUMBER) ? JsonToken.VALUE_NUMBER_INT : JsonToken.VALUE_STRING);
      if (properties.put(name, parser.getString()) != null) {
        throw notLegs("a leg has " + name + " twice");
      }
    }
    return properties;
  }

  private static Leg leg(Map<String, String> properties) {
    if (!properties.keySet().equals(PROPERTIES)) {
      throw notLegs("a leg has " + properties.keySet() + " instead of " + PROPERTIES);
    }
    return new Leg(
        new Carrier(properties.get(CARRIER)),
        Integer.parseInt(properties.get(FLIGHT_NUMBER)),
        new Airport(properties.get(ORIGIN)),
        new Airport(properties.get(DESTINATION)),
        OffsetDateTime.parse(properties.get(DEPARTURE)),
        OffsetDateTime.parse(properties.get(ARRIVAL)),
        new Money(new BigDecimal(properties.get(PRICE))),
        Provider.valueOf(properties.get(PROVIDER)),
        Instant.parse(properties.get(QUOTED_AT)));
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
