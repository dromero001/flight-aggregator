package com.flightaggregator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void normalisesToCents() {
    assertThat(Money.euros("129.9")).isEqualTo(Money.euros("129.90"));
    assertThat(Money.euros("129.9").amount().scale()).isEqualTo(2);
  }

  @Test
  void roundsExtraDecimalsHalfUp() {
    assertThat(Money.euros("129.995")).isEqualTo(Money.euros("130.00"));
    assertThat(Money.euros("129.994")).isEqualTo(Money.euros("129.99"));
    assertThat(Money.euros("129.985")).isEqualTo(Money.euros("129.99"));
  }

  @Test
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> Money.euros("-0.01")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.euros("-0.001")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addsExactly() {
    assertThat(Money.euros("100.00").plus(Money.euros("29.90"))).isEqualTo(Money.euros("129.90"));
  }

  @Test
  void multipliesRoundingHalfUp() {
    assertThat(Money.euros("10.10").times(new BigDecimal("1.05"))).isEqualTo(Money.euros("10.61"));
  }

  @Test
  void ordersByAmount() {
    assertThat(Money.euros("9.99")).isLessThan(Money.euros("10.00"));
    assertThat(Money.euros("10.0")).isEqualByComparingTo(Money.euros("10.00"));
  }
}
