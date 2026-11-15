package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.model.LegKey;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Which of a provider's records a request asks for. */
public record RecordFilter(Optional<String> origin, Optional<LegKey> flight) {

  public static RecordFilter all() {
    return new RecordFilter(Optional.empty(), Optional.empty());
  }

  public static RecordFilter fromOrigin(String origin) {
    return new RecordFilter(Optional.of(origin), Optional.empty());
  }

  public static RecordFilter flight(LegKey flight) {
    return new RecordFilter(Optional.empty(), Optional.of(flight));
  }

  /**
   * The filter a listing request asks for: its {@code origin}, or, when the whole flight key is
   * given, that one flight. A flight key with a missing part is refused.
   */
  public static RecordFilter of(
      String origin, String carrier, String flightNumber, String destination, String departure) {
    if (carrier == null && flightNumber == null && destination == null && departure == null) {
      return origin == null ? all() : fromOrigin(origin);
    }
    if (carrier == null
        || flightNumber == null
        || origin == null
        || destination == null
        || departure == null) {
      throw new InvalidRequestException(
          "A flight is asked for by carrier, flightNumber, origin, destination and departure");
    }
    return flight(
        new LegKey(
            carrier,
            Paging.integer("Flight number", flightNumber, 0),
            origin,
            destination,
            instant(departure)));
  }

  private static Instant instant(String raw) {
    if (!raw.endsWith("Z")) {
      throw new InvalidRequestException("Departure must be a UTC instant ending in Z: " + raw);
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new InvalidRequestException("Departure must be a UTC instant: " + raw, e);
    }
  }

  boolean matches(ProviderRecord record) {
    return origin.map(o -> o.equals(record.leg().origin())).orElse(true)
        && flight.map(f -> f.equals(record.leg().key())).orElse(true);
  }
}
