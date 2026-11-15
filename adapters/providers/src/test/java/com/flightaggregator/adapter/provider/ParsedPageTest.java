package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParsedPageTest {

  @Test
  void rejectsANullNextPosition() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ParsedPage(List.of(), List.of(), null))
        .withMessage("next");
  }
}
