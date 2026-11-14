package com.flightaggregator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AirportTest {

  @Test
  void acceptsThreeUpperCaseLetters() {
    assertThat(new Airport("MAD").code()).isEqualTo("MAD");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MA", "MADR", "mad", "M4D", ""})
  void rejectsMalformedCodes(String code) {
    assertThatThrownBy(() -> new Airport(code)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> new Airport(null)).isInstanceOf(NullPointerException.class);
  }
}
