package com.flightaggregator.adapter.provider.alpha;

import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.RecordFields;
import com.flightaggregator.adapter.provider.RecordRejected;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parses one page of Alpha's flat JSON list; a record that is not valid is discarded alone. */
final class AlphaPageParser {

  private static final int EXCERPT_LIMIT = 200;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AlphaPageParser() {}

  static ParsedPage parse(String body, Instant quotedAt) {
    JsonNode page = readPage(body);
    JsonNode records = page.path("legs");
    if (!records.isArray()) {
      throw malformed(null);
    }
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    for (JsonNode record : records) {
      try {
        legs.add(leg(record, quotedAt));
      } catch (RecordRejected rejected) {
        discarded.add(new DiscardedRecord(rejected.reason(), excerpt(record)));
      }
    }
    return new ParsedPage(legs, discarded, nextCursor(page.path("nextCursor")));
  }

  private static String excerpt(JsonNode record) {
    String text = record.toString();
    return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT);
  }

  private static JsonNode readPage(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (JacksonException e) {
      throw malformed(e);
    }
  }

  /** The cursor is a string, or {@code null} on the last page: anything else would end early. */
  private static Optional<String> nextCursor(JsonNode cursor) {
    if (cursor.isNull()) {
      return Optional.empty();
    }
    if (!cursor.isString()) {
      throw malformed(null);
    }
    return Optional.of(cursor.asString());
  }

  private static ProviderUnavailableException malformed(Throwable cause) {
    return new ProviderUnavailableException(Provider.ALPHA, FailureReason.MALFORMED_PAGE, cause);
  }

  private static Leg leg(JsonNode record, Instant quotedAt) {
    if (!record.isObject()) {
      throw new RecordRejected(DiscardReason.SHAPE);
    }
    return RecordFields.leg(
        RecordFields.carrier(text(record, "carrier", DiscardReason.CARRIER)),
        RecordFields.flightNumber(integer(record, "flightNumber", DiscardReason.FLIGHT_NUMBER)),
        RecordFields.airport(text(record, "origin", DiscardReason.AIRPORT)),
        RecordFields.airport(text(record, "destination", DiscardReason.AIRPORT)),
        RecordFields.time(text(record, "departureTime", DiscardReason.TIME)),
        RecordFields.time(text(record, "arrivalTime", DiscardReason.TIME)),
        RecordFields.decimalPrice(text(record, "price", DiscardReason.PRICE)),
        Provider.ALPHA,
        quotedAt);
  }

  /**
   * The field's text, or {@code null} when it is missing, which {@link RecordFields} rejects with
   * the field's own reason; a value of another JSON type is rejected here with the same reason.
   */
  private static String field(
      JsonNode record, String name, Predicate<JsonNode> hasRightType, DiscardReason reason) {
    JsonNode value = record.get(name);
    if (value == null) {
      return null;
    }
    if (!hasRightType.test(value)) {
      throw new RecordRejected(reason);
    }
    return value.asString();
  }

  private static String text(JsonNode record, String name, DiscardReason reason) {
    return field(record, name, JsonNode::isString, reason);
  }

  private static String integer(JsonNode record, String name, DiscardReason reason) {
    return field(record, name, JsonNode::isIntegralNumber, reason);
  }
}
