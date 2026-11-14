package com.flightaggregator.domain;

import static com.flightaggregator.domain.Legs.offer;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PricingPolicyTest {

  @Test
  void addsAFivePercentMarkupToTheSumOfTheLegs() {
    List<Leg> legs =
        List.of(
            offer(
                "IB3170",
                "MAD-LHR",
                "2026-11-20T08:15+01:00",
                "2026-11-20T09:30+00:00",
                "100.00",
                Provider.ALPHA),
            offer(
                "BA117",
                "LHR-JFK",
                "2026-11-20T11:10+00:00",
                "2026-11-20T14:20-05:00",
                "29.90",
                Provider.ALPHA));

    assertThat(PricingPolicy.finalPrice(legs)).isEqualTo(Money.euros("136.40"));
  }

  @Test
  void roundsHalfUp() {
    assertThat(PricingPolicy.finalPrice(List.of(priced("10.10")))).isEqualTo(Money.euros("10.61"));
  }

  @Test
  void roundsTheTotalOnceNotEachLeg() {
    List<Leg> legs = List.of(priced("0.10"), priced("0.10"), priced("0.10"));

    assertThat(PricingPolicy.finalPrice(legs)).isEqualTo(Money.euros("0.32"));
  }

  private static Leg priced(String price) {
    return offer(
        "IB3170",
        "MAD-LHR",
        "2026-11-20T08:15+01:00",
        "2026-11-20T09:30+00:00",
        price,
        Provider.ALPHA);
  }
}
