package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecordFieldsTest {

  @Test
  void parsesAValidDecimalPrice() {
    assertThat(RecordFields.decimalPrice("129.90")).isEqualTo(Money.euros("129.90"));
    assertThat(RecordFields.decimalPrice("7")).isEqualTo(Money.euros("7.00"));
    assertThat(RecordFields.decimalPrice("10.005")).isEqualTo(Money.euros("10.01"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1e3", "-129.90", "1234567890.00", "999999999.995"})
  void rejectsAnExponentANegativeAndAnOverlongPrice(String text) {
    assertRejected(() -> RecordFields.decimalPrice(text), DiscardReason.PRICE);
  }

  @Test
  void parsesCents() {
    assertThat(RecordFields.centsPrice("12990")).isEqualTo(Money.euros("129.90"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"129.90", "-12990", "1e3", "123456789012", ""})
  void rejectsCentsThatAreNotWholeDigits(String text) {
    assertRejected(() -> RecordFields.centsPrice(text), DiscardReason.PRICE);
  }

  @Test
  void parsesATimeWithItsOffset() {
    OffsetDateTime time = RecordFields.time("2026-11-20T08:15:00+01:00");

    assertThat(time).isEqualTo(OffsetDateTime.parse("2026-11-20T08:15:00+01:00"));
    assertThat(time.getOffset().getTotalSeconds()).isEqualTo(3600);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-11-20T08:15",
        "2026-11-20T08:15:00",
        "2026-11-20T08:15+01:00",
        "2026-02-30T25:61:00+01:00"
      })
  void rejectsATimeWithoutOffsetOrSeconds(String text) {
    assertRejected(() -> RecordFields.time(text), DiscardReason.TIME);
  }

  @Test
  void parsesAFlightNumberACarrierAndAnAirport() {
    assertThat(RecordFields.flightNumber("3166")).isEqualTo(3166);
    assertThat(RecordFields.carrier("IB")).isEqualTo(new Carrier("IB"));
    assertThat(RecordFields.airport("MAD")).isEqualTo(new Airport("MAD"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"12345", "-1", "1e2", "", "A1"})
  void rejectsABadFlightNumber(String text) {
    assertRejected(() -> RecordFields.flightNumber(text), DiscardReason.FLIGHT_NUMBER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ib", "IBE", "I", ""})
  void rejectsABadCarrier(String text) {
    assertRejected(() -> RecordFields.carrier(text), DiscardReason.CARRIER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mad", "MADR", "M1D", ""})
  void rejectsABadAirport(String text) {
    assertRejected(() -> RecordFields.airport(text), DiscardReason.AIRPORT);
  }

  @ParameterizedTest
  @MethodSource("fieldsWithTheirReasons")
  void rejectsAMissingValueWithTheFieldsOwnReason(
      Function<String, Object> parse, DiscardReason reason) {
    assertRejected(() -> parse.apply(null), reason);
  }

  static Stream<Arguments> fieldsWithTheirReasons() {
    return Stream.of(
        arguments((Function<String, Object>) RecordFields::decimalPrice, DiscardReason.PRICE),
        arguments((Function<String, Object>) RecordFields::centsPrice, DiscardReason.PRICE),
        arguments((Function<String, Object>) RecordFields::time, DiscardReason.TIME),
        arguments(
            (Function<String, Object>) RecordFields::flightNumber, DiscardReason.FLIGHT_NUMBER),
        arguments((Function<String, Object>) RecordFields::carrier, DiscardReason.CARRIER),
        arguments((Function<String, Object>) RecordFields::airport, DiscardReason.AIRPORT));
  }

  @Test
  void rejectsALegTheDomainRejects() {
    OffsetDateTime departure = OffsetDateTime.parse("2026-11-20T08:15:00+01:00");

    assertRejected(
        () ->
            RecordFields.leg(
                new Carrier("IB"),
                3166,
                new Airport("MAD"),
                new Airport("LHR"),
                departure,
                departure.minusHours(1),
                Money.euros("129.90"),
                Provider.ALPHA,
                Instant.parse("2026-11-15T10:00:00Z")),
        DiscardReason.LEG);
  }

  @Test
  void buildsALegTheDomainAccepts() {
    OffsetDateTime departure = OffsetDateTime.parse("2026-11-20T08:15:00+01:00");
    Instant quotedAt = Instant.parse("2026-11-15T10:00:00Z");

    Leg leg =
        RecordFields.leg(
            new Carrier("IB"),
            3166,
            new Airport("MAD"),
            new Airport("LHR"),
            departure,
            departure.plusHours(2),
            Money.euros("129.90"),
            Provider.ALPHA,
            quotedAt);

    assertThat(leg.flightNumber()).isEqualTo(3166);
    assertThat(leg.quotedAt()).isEqualTo(quotedAt);
  }

  private static void assertRejected(ThrowingCallable call, DiscardReason reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            RecordRejected.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }
}
