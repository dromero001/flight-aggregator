package com.flightaggregator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CarrierTest {

  @ParameterizedTest
  @ValueSource(strings = {"IB", "U2"})
  void acceptsTwoUpperCaseLettersOrDigits(String code) {
    assertThat(new Carrier(code).code()).isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(strings = {"I", "IBE", "ib", "I-"})
  void rejectsMalformedCodes(String code) {
    assertThatThrownBy(() -> new Carrier(code)).isInstanceOf(IllegalArgumentException.class);
  }
}
