package com.flightaggregator.domain;

import java.math.BigDecimal;
import java.util.List;

/** How an itinerary is priced: the sum of its legs plus a markup, rounded once. */
final class PricingPolicy {

  static final BigDecimal MARKUP_RATE = new BigDecimal("0.05");

  private PricingPolicy() {}

  static Money finalPrice(List<Leg> legs) {
    Money sum = legs.stream().map(Leg::price).reduce(Money.ZERO, Money::plus);
    return sum.times(BigDecimal.ONE.add(MARKUP_RATE));
  }
}
