package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class RenderedLegTest {

  @Test
  void rendersAValidLegAsItIs() {
    var rendered = RenderedLeg.of(new PricedRecord(new ValidLeg(leg()), new BigDecimal("131.20")));

    assertThat(rendered)
        .isEqualTo(
            new RenderedLeg(
                "IB",
                3170,
                "MAD",
                "LHR",
                "2026-11-20T08:15:00+01:00",
                "2026-11-20T09:30:00+00:00",
                new BigDecimal("131.20")));
  }

  @Test
  void omitsTheFlightNumberOfAMissingFieldRecord() {
    assertThat(rendered(Defect.MISSING_FIELD).flightNumber()).isNull();
  }

  @Test
  void writesTheInvalidDateTextForABadDateRecord() {
    assertThat(rendered(Defect.BAD_DATE).departureTime()).isEqualTo("2026-02-30T25:61:00+01:00");
  }

  @Test
  void negatesThePriceOfANegativePriceRecord() {
    assertThat(rendered(Defect.NEGATIVE_PRICE).price()).isEqualTo(new BigDecimal("-131.20"));
  }

  @Test
  void writesTheUnknownAirportCodeForAnUnknownAirportRecord() {
    assertThat(rendered(Defect.UNKNOWN_AIRPORT).destination()).isEqualTo("ZZZ");
  }

  private static Leg leg() {
    return new Leg(
        "IB",
        3170,
        "MAD",
        "LHR",
        OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
        OffsetDateTime.parse("2026-11-20T09:30:00Z"),
        new BigDecimal("129.90"),
        Provider.ALPHA);
  }

  private static RenderedLeg rendered(Defect defect) {
    return RenderedLeg.of(
        new PricedRecord(new MalformedLeg(leg(), defect), new BigDecimal("131.20")));
  }
}
