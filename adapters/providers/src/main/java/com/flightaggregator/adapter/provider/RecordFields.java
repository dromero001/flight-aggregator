package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Strict, per-field parsing of a provider record: a field that is missing ({@code null}) or not
 * exact is rejected with that field's own reason.
 */
final class RecordFields {

  private static final Pattern DECIMAL_PRICE = Pattern.compile("^[0-9]{1,9}(\\.[0-9]{1,6})?$");

  private static final Pattern CENTS_PRICE = Pattern.compile("^[0-9]{1,11}$");

  /** ISO-8601 with seconds and an offset: {@code OffsetDateTime.parse} alone accepts no seconds. */
  private static final Pattern TIME =
      Pattern.compile(
          "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$");

  private static final Pattern FLIGHT_NUMBER = Pattern.compile("^[0-9]{1,4}$");

  private RecordFields() {}

  static Money decimalPrice(String text) {
    if (text == null || !DECIMAL_PRICE.matcher(text).matches()) {
      throw new RecordRejected(DiscardReason.PRICE);
    }
    return money(new BigDecimal(text));
  }

  static Money centsPrice(String text) {
    if (text == null || !CENTS_PRICE.matcher(text).matches()) {
      throw new RecordRejected(DiscardReason.PRICE);
    }
    return money(BigDecimal.valueOf(Long.parseLong(text), 2));
  }

  static OffsetDateTime time(String text) {
    if (text == null || !TIME.matcher(text).matches()) {
      throw new RecordRejected(DiscardReason.TIME);
    }
    try {
      return OffsetDateTime.parse(text);
    } catch (DateTimeParseException e) {
      throw new RecordRejected(DiscardReason.TIME);
    }
  }

  static int flightNumber(String text) {
    if (text == null || !FLIGHT_NUMBER.matcher(text).matches()) {
      throw new RecordRejected(DiscardReason.FLIGHT_NUMBER);
    }
    return Integer.parseInt(text);
  }

  static Carrier carrier(String text) {
    if (text == null) {
      throw new RecordRejected(DiscardReason.CARRIER);
    }
    try {
      return new Carrier(text);
    } catch (IllegalArgumentException e) {
      throw new RecordRejected(DiscardReason.CARRIER);
    }
  }

  static Airport airport(String text) {
    if (text == null) {
      throw new RecordRejected(DiscardReason.AIRPORT);
    }
    try {
      return new Airport(text);
    } catch (IllegalArgumentException e) {
      throw new RecordRejected(DiscardReason.AIRPORT);
    }
  }

  /** Builds the leg, turning the domain's refusal of it into a rejected record. */
  static Leg leg(
      Carrier carrier,
      int flightNumber,
      Airport origin,
      Airport destination,
      OffsetDateTime departure,
      OffsetDateTime arrival,
      Money price,
      Provider provider,
      Instant quotedAt) {
    try {
      return new Leg(
          carrier,
          flightNumber,
          origin,
          destination,
          departure,
          arrival,
          price,
          provider,
          quotedAt);
    } catch (IllegalArgumentException e) {
      throw new RecordRejected(DiscardReason.LEG);
    }
  }

  /** A price the pattern accepts can still be out of the domain's bound, such as 999999999.995. */
  private static Money money(BigDecimal amount) {
    try {
      return new Money(amount);
    } catch (IllegalArgumentException e) {
      throw new RecordRejected(DiscardReason.PRICE);
    }
  }
}
