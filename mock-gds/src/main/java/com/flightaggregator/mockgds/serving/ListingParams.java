package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.model.LegKey;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The query parameters every listing shares besides its paging: an {@code origin} filter, or the
 * five parts of a flight key that make the listing a quote. Bound by name, so no part can be
 * swapped by position; {@link #toFilter()} validates them.
 *
 * @param origin IATA code of the departure airport
 * @param carrier IATA code of the airline
 * @param flightNumber numeric part of the flight number
 * @param destination IATA code of the arrival airport
 * @param departure UTC instant ending in {@code Z}, unlike the times a provider writes
 */
public record ListingParams(
    String origin, String carrier, String flightNumber, String destination, String departure) {

  private static final Pattern AIRPORT = Pattern.compile("[A-Z]{3}");
  private static final Pattern AIRLINE = Pattern.compile("[A-Z0-9]{2}");

  /**
   * The records asked for: all, one origin's, or, when the whole flight key is given, that one
   * flight. A flight key with a missing part, or a malformed part, is refused.
   */
  public RecordFilter toFilter() {
    var airport = shaped("Origin", origin, AIRPORT);
    if (carrier == null && flightNumber == null && destination == null && departure == null) {
      return airport == null ? RecordFilter.all() : RecordFilter.fromOrigin(airport);
    }
    if (carrier == null
        || flightNumber == null
        || airport == null
        || destination == null
        || departure == null) {
      throw new InvalidRequestException(
          "A flight is asked for by carrier, flightNumber, origin, destination and departure");
    }
    return RecordFilter.flight(
        new LegKey(
            shaped("Carrier", carrier, AIRLINE),
            Paging.integer("Flight number", flightNumber, 0),
            airport,
            shaped("Destination", destination, AIRPORT),
            instant(departure)));
  }

  private static String shaped(String name, String raw, Pattern shape) {
    if (raw != null && !shape.matcher(raw).matches()) {
      throw new InvalidRequestException(name + " is not a valid code: " + raw);
    }
    return raw;
  }

  private static Instant instant(String raw) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new InvalidRequestException("Departure must be a UTC instant: " + raw, e);
    }
  }
}
