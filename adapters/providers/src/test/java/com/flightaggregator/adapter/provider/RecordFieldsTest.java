package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RecordFieldsTest {

  @Test
  void parsesAValidDecimalPrice() {
    assertThat(RecordFields.decimalPrice("129.90")).isEqualTo(Money.euros("129.90"));
    assertThat(RecordFields.decimalPrice("7")).isEqualTo(Money.euros("7.00"));
    assertThat(RecordFields.decimalPrice("10.005")).isEqualTo(Money.euros("10.01"));
  }

  @Test
  void rejectsAnExponentANegativeAndAnOverlongPrice() {
    for (String text : new String[] {"1e3", "-129.90", "1234567890.00"}) {
      assertThatThrownBy(() -> RecordFields.decimalPrice(text))
          .as(text)
          .isInstanceOfSatisfying(
              RecordRejected.class, e -> assertThat(e.reason()).isEqualTo(DiscardReason.PRICE));
    }
  }

  @Test
  void parsesCents() {
    assertThat(RecordFields.centsPrice("12990")).isEqualTo(Money.euros("129.90"));
  }

  @Test
  void rejectsCentsThatAreNotWholeDigits() {
    for (String text : new String[] {"129.90", "-12990", "1e3", "123456789012", ""}) {
      assertThatThrownBy(() -> RecordFields.centsPrice(text))
          .as(text)
          .isInstanceOfSatisfying(
              RecordRejected.class, e -> assertThat(e.reason()).isEqualTo(DiscardReason.PRICE));
    }
  }

  @Test
  void parsesATimeWithItsOffset() {
    OffsetDateTime time = RecordFields.time("2026-11-20T08:15:00+01:00");

    assertThat(time).isEqualTo(OffsetDateTime.parse("2026-11-20T08:15:00+01:00"));
    assertThat(time.getOffset().getTotalSeconds()).isEqualTo(3600);
  }

  @Test
  void rejectsATimeWithoutOffsetOrSeconds() {
    for (String text :
        new String[] {
          "2026-11-20T08:15",
          "2026-11-20T08:15:00",
          "2026-11-20T08:15+01:00",
          "2026-02-30T25:61:00+01:00"
        }) {
      assertThatThrownBy(() -> RecordFields.time(text))
          .as(text)
          .isInstanceOfSatisfying(
              RecordRejected.class, e -> assertThat(e.reason()).isEqualTo(DiscardReason.TIME));
    }
  }

  @Test
  void parsesAFlightNumberACarrierAndAnAirport() {
    assertThat(RecordFields.flightNumber("3166")).isEqualTo(3166);
    assertThat(RecordFields.carrier("IB")).isEqualTo(new Carrier("IB"));
    assertThat(RecordFields.airport("MAD")).isEqualTo(new Airport("MAD"));
  }

  @Test
  void rejectsABadFlightNumberCarrierOrAirport() {
    for (String text : new String[] {"12345", "-1", "1e2", "", "A1"}) {
      assertRejected(() -> RecordFields.flightNumber(text), DiscardReason.FLIGHT_NUMBER);
    }
    for (String text : new String[] {"ib", "IBE", "I", ""}) {
      assertRejected(() -> RecordFields.carrier(text), DiscardReason.CARRIER);
    }
    for (String text : new String[] {"mad", "MADR", "M1D", ""}) {
      assertRejected(() -> RecordFields.airport(text), DiscardReason.AIRPORT);
    }
  }

  private static void assertRejected(ThrowingCallable call, DiscardReason reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            RecordRejected.class, e -> assertThat(e.reason()).isEqualTo(reason));
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
}
