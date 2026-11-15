package com.flightaggregator.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscardedRecordTest {

  @Test
  void keepsAShortExcerptWhole() {
    assertThat(new DiscardedRecord(DiscardReason.SHAPE, "a".repeat(200)).excerpt())
        .isEqualTo("a".repeat(200));
  }

  @Test
  void cutsALongExcerptAtTwoHundredCharacters() {
    assertThat(new DiscardedRecord(DiscardReason.SHAPE, "a".repeat(300)).excerpt())
        .isEqualTo("a".repeat(200));
  }

  @Test
  void neverSplitsASurrogatePair() {
    String text = "a".repeat(199) + "😀" + "tail";

    assertThat(new DiscardedRecord(DiscardReason.SHAPE, text).excerpt()).isEqualTo("a".repeat(199));
  }
}
