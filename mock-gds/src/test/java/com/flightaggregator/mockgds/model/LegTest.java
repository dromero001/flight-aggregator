package com.flightaggregator.mockgds.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class LegTest {

  private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-20T08:15:00+01:00");
  private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-11-20T09:35:00Z");

  @Test
  void itsKeyIdentifiesTheFlightRegardlessOfProviderPriceAndOffset() {
    var alpha = leg(DEPARTURE, new BigDecimal("129.90"), Provider.ALPHA);
    var beta =
        leg(
            DEPARTURE.withOffsetSameInstant(java.time.ZoneOffset.UTC),
            new BigDecimal("119.00"),
            Provider.BETA);

    assertThat(alpha.key()).isEqualTo(beta.key());
    assertThat(alpha.key()).isEqualTo(new LegKey("IB", 3170, "MAD", "LHR", DEPARTURE.toInstant()));
  }

  @Test
  void theKeyReadsLikeAFlightOnABoardingPass() {
    assertThat(new LegKey("IB", 3170, "MAD", "LHR", DEPARTURE.toInstant()))
        .hasToString("IB3170 MAD-LHR 2026-11-20T07:15:00Z");
  }

  @Test
  void rejectsAnArrivalThatIsNotAfterTheDeparture() {
    assertThatThrownBy(
            () ->
                new Leg(
                    "IB",
                    3170,
                    "MAD",
                    "LHR",
                    DEPARTURE,
                    DEPARTURE,
                    new BigDecimal("10.00"),
                    Provider.ALPHA))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsPricesThatAreNotPositiveEurosWithTwoDecimals() {
    assertThatThrownBy(() -> leg(DEPARTURE, new BigDecimal("0.00"), Provider.ALPHA))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> leg(DEPARTURE, new BigDecimal("10.5"), Provider.ALPHA))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Leg leg(OffsetDateTime departure, BigDecimal price, Provider provider) {
    return new Leg("IB", 3170, "MAD", "LHR", departure, ARRIVAL, price, provider);
  }
}
