package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Money;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

/**
 * A page of search results as the JSON document the search cache keeps: its itineraries, each the
 * array of its legs as {@link LegsJson} writes it, the next page's cursor field by field or {@code
 * null}, and whether the inventory changed. Only this class writes the document, so it is read back
 * in the order it was written.
 */
final class SearchPageJson {

  private static final String ITINERARIES = "itineraries";
  private static final String NEXT = "next";
  private static final String INVENTORY_CHANGED = "inventoryChanged";
  private static final String GENERATION = "generation";
  private static final String SORT = "sort";
  private static final String PRICE = "price";
  private static final String DEPARTURE = "departure";
  private static final String DURATION = "duration";
  private static final String ID = "id";

  private SearchPageJson() {}

  static String write(SearchPage page, JsonFactory json) {
    StringWriter text = new StringWriter();
    try (JsonGenerator generator = json.createGenerator(ObjectWriteContext.empty(), text)) {
      generator.writeStartObject();
      generator.writeName(ITINERARIES);
      generator.writeStartArray();
      for (Itinerary itinerary : page.itineraries()) {
        LegsJson.write(itinerary.legs(), generator);
      }
      generator.writeEndArray();
      generator.writeName(NEXT);
      page.next().ifPresentOrElse(cursor -> write(cursor, generator), generator::writeNull);
      generator.writeBooleanProperty(INVENTORY_CHANGED, page.inventoryChanged());
      generator.writeEndObject();
    }
    return text.toString();
  }

  /**
   * The page of a document {@link #write} produced. A property out of its place, a value of the
   * wrong type or one that does not parse, or any content after the page, is an {@link
   * IllegalStateException}.
   */
  static SearchPage read(String document, JsonFactory json) {
    try (JsonParser parser = json.createParser(ObjectReadContext.empty(), document)) {
      expect(parser.nextToken(), JsonToken.START_OBJECT);
      expect(value(parser, ITINERARIES), JsonToken.START_ARRAY);
      List<Itinerary> itineraries = new ArrayList<>();
      while (parser.nextToken() == JsonToken.START_ARRAY) {
        itineraries.add(new Itinerary(LegsJson.read(parser)));
      }
      expect(parser.currentToken(), JsonToken.END_ARRAY);
      Optional<PageCursor> next =
          value(parser, NEXT) == JsonToken.VALUE_NULL
              ? Optional.empty()
              : Optional.of(cursor(parser));
      boolean inventoryChanged = bool(value(parser, INVENTORY_CHANGED));
      expect(parser.nextToken(), JsonToken.END_OBJECT);
      expect(parser.nextToken(), null);
      return new SearchPage(List.copyOf(itineraries), next, inventoryChanged);
    } catch (JacksonException | IllegalArgumentException | DateTimeException e) {
      throw notAPage(e);
    }
  }

  private static void write(PageCursor cursor, JsonGenerator generator) {
    SeekPosition position = cursor.position();
    generator.writeStartObject();
    generator.writeNumberProperty(GENERATION, cursor.generation());
    generator.writeStringProperty(SORT, cursor.sort().name());
    generator.writeStringProperty(PRICE, position.price().amount().toPlainString());
    generator.writeStringProperty(DEPARTURE, position.departure().toString());
    generator.writeStringProperty(DURATION, position.duration().toString());
    generator.writeStringProperty(ID, position.id().value().toString());
    generator.writeEndObject();
  }

  /** The cursor whose object the parser is at, leaving the parser at the object's end. */
  private static PageCursor cursor(JsonParser parser) {
    expect(parser.currentToken(), JsonToken.START_OBJECT);
    expect(value(parser, GENERATION), JsonToken.VALUE_NUMBER_INT);
    long generation = parser.getLongValue();
    SortOrder sort = SortOrder.valueOf(string(parser, SORT));
    Money price = new Money(new BigDecimal(string(parser, PRICE)));
    Instant departure = Instant.parse(string(parser, DEPARTURE));
    Duration duration = Duration.parse(string(parser, DURATION));
    ItineraryId id = new ItineraryId(UUID.fromString(string(parser, ID)));
    expect(parser.nextToken(), JsonToken.END_OBJECT);
    return new PageCursor(generation, sort, new SeekPosition(price, departure, duration, id));
  }

  /** Moves to the value of the next property, which must be {@code name}, and returns its token. */
  private static JsonToken value(JsonParser parser, String name) {
    expect(parser.nextToken(), JsonToken.PROPERTY_NAME);
    if (!parser.currentName().equals(name)) {
      throw notAPage("expected " + name + " but found " + parser.currentName());
    }
    return parser.nextToken();
  }

  private static String string(JsonParser parser, String name) {
    expect(value(parser, name), JsonToken.VALUE_STRING);
    return parser.getString();
  }

  private static boolean bool(JsonToken token) {
    if (!token.isBoolean()) {
      throw notAPage("expected a boolean but found " + token);
    }
    return token == JsonToken.VALUE_TRUE;
  }

  private static void expect(JsonToken actual, JsonToken expected) {
    if (actual != expected) {
      throw notAPage("expected " + expected + " but found " + actual);
    }
  }

  private static IllegalStateException notAPage(String why) {
    return new IllegalStateException("the cached document is not a search page: " + why);
  }

  private static IllegalStateException notAPage(Exception cause) {
    return new IllegalStateException("the cached document is not a search page", cause);
  }
}
