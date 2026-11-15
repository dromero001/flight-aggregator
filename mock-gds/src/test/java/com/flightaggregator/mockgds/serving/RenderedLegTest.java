package com.flightaggregator.mockgds.serving;

import static com.flightaggregator.mockgds.support.Fixtures.ib3170;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Provider;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RenderedLegTest {

  @Test
  void rendersAValidLegAsItIs() {
    var rendered =
        RenderedLeg.of(
            new PricedRecord(new ValidLeg(ib3170(Provider.ALPHA)), new BigDecimal("131.20")));

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

  private static RenderedLeg rendered(Defect defect) {
    return RenderedLeg.of(
        new PricedRecord(
            new MalformedLeg(ib3170(Provider.ALPHA), defect), new BigDecimal("131.20")));
  }
}
