package com.flightaggregator.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** An amount of euros, kept in cents. */
public record Money(BigDecimal amount) implements Comparable<Money> {

  /**
   * Amounts from here up are rejected before rounding: a bound against malformed input, such as a
   * huge exponent, not a price ceiling.
   */
  private static final BigDecimal ONE_BILLION = new BigDecimal("1E+9");

  /**
   * Amounts below this round to zero, so they become zero without rounding: rounding one with a
   * huge scale, such as {@code 1E-100000000}, would take seconds.
   */
  private static final BigDecimal HALF_A_CENT = new BigDecimal("0.005");

  public static final Money ZERO = new Money(BigDecimal.ZERO);

  public Money {
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("Money cannot be negative: " + amount);
    }
    if (amount.compareTo(ONE_BILLION) >= 0) {
      throw new IllegalArgumentException("Money must be under one billion euros: " + amount);
    }
    amount =
        amount.compareTo(HALF_A_CENT) < 0
            ? BigDecimal.valueOf(0, 2)
            : amount.setScale(2, RoundingMode.HALF_UP);
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
