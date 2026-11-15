package com.flightaggregator.adapter.provider;

import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.ProviderText;
import com.flightaggregator.domain.Leg;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One page of Alpha: a flat JSON list of legs, {@code limit} at most, paged by an opaque cursor
 * that is {@code null} on the last page. A record that is not valid is discarded alone.
 */
record AlphaPage(int limit, Optional<String> cursor) implements PageRequest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The page at a cursor, empty for the first page. */
  static AlphaPage at(Optional<String> cursor, int pageSize) {
    return new AlphaPage(pageSize, cursor);
  }

  @Override
  public String pathTemplate() {
    return cursor.isEmpty()
        ? "/alpha/legs?limit={limit}"
        : "/alpha/legs?limit={limit}&cursor={cursor}";
  }

  @Override
  public Object[] variables() {
    return cursor.isEmpty() ? new Object[] {limit} : new Object[] {limit, cursor.get()};
  }

  @Override
  public ParsedPage parse(FetchedPage fetched) {
    JsonNode page = readPage(fetched.body());
    JsonNode records = page.path("legs");
    if (!records.isArray()) {
      throw new PageMalformed("The page has no \"legs\" array");
    }
    if (records.size() > limit) {
      throw new PageMalformed("Asked for at most " + limit + " records, got " + records.size());
    }
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    for (JsonNode record : records) {
      try {
        legs.add(leg(record, fetched));
      } catch (RecordRejected rejected) {
        discarded.add(new DiscardedRecord(rejected.reason(), record.toString()));
      }
    }
    return new ParsedPage(legs, discarded, nextCursor(page.path("nextCursor")));
  }

  private static JsonNode readPage(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (JacksonException e) {
      throw new PageMalformed("The page is not JSON", e);
    }
  }

  /** The cursor is a string, or {@code null} on the last page: anything else would end early. */
  private static Optional<String> nextCursor(JsonNode cursor) {
    if (cursor.isNull()) {
      return Optional.empty();
    }
    if (!cursor.isString()) {
      throw new PageMalformed(
          "The page's nextCursor is neither a string nor null: "
              + ProviderText.quoted(cursor.toString()));
    }
    return Optional.of(cursor.asString());
  }

  private static Leg leg(JsonNode record, FetchedPage fetched) {
    if (!record.isObject()) {
      throw new RecordRejected(DiscardReason.SHAPE);
    }
    return RecordFields.leg(
        RecordFields.carrier(text(record, "carrier")),
        RecordFields.flightNumber(integer(record, "flightNumber")),
        RecordFields.airport(text(record, "origin")),
        RecordFields.airport(text(record, "destination")),
        RecordFields.time(text(record, "departureTime")),
        RecordFields.time(text(record, "arrivalTime")),
        RecordFields.decimalPrice(text(record, "price")),
        fetched.provider(),
        fetched.receivedAt());
  }

  /**
   * The field's text; {@code null} when it is missing or not a string, which {@link RecordFields}
   * rejects with the field's own reason.
   */
  private static String text(JsonNode record, String name) {
    return textOf(record.get(name), JsonNode::isString);
  }

  /** As {@link #text}, for a field that must be a JSON integer. */
  private static String integer(JsonNode record, String name) {
    return textOf(record.get(name), JsonNode::isIntegralNumber);
  }

  private static String textOf(JsonNode value, Predicate<JsonNode> hasRightType) {
    return value != null && hasRightType.test(value) ? value.asString() : null;
  }
}
