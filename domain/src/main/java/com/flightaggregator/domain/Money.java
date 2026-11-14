package com.flightaggregator.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** An amount of euros, kept in cents. */
public record Money(BigDecimal amount) implements Comparable<Money> {

  public static final Money ZERO = new Money(BigDecimal.ZERO);

  public Money {
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("Money cannot be negative: " + amount);
    }
    amount = amount.setScale(2, RoundingMode.HALF_UP);
  }

  public Money plus(Money other) {
    return new Money(amount.add(other.amount));
  }

  public Money times(BigDecimal factor) {
    return new Money(amount.multiply(factor));
  }

  @Override
  public int compareTo(Money other) {
    return amount.compareTo(other.amount);
  }

  public static Money euros(String amount) {
    return new Money(new BigDecimal(amount));
  }
}
