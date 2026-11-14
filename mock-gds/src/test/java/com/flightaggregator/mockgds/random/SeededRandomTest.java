package com.flightaggregator.mockgds.random;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeededRandomTest {

  @Test
  void theSameSeedAndKeyAlwaysGiveTheSameSequence() {
    var first = SeededRandom.derive(42, "IB", "3170", "2026-11-20");
    var second = SeededRandom.derive(42, "IB", "3170", "2026-11-20");

    assertThat(first.longs(5).toArray()).isEqualTo(second.longs(5).toArray());
  }

  @Test
  void aDifferentSeedOrKeyGivesADifferentSequence() {
    long reference = SeededRandom.derive(42, "IB", "3170").nextLong();

    assertThat(SeededRandom.derive(43, "IB", "3170").nextLong()).isNotEqualTo(reference);
    assertThat(SeededRandom.derive(42, "IB", "3171").nextLong()).isNotEqualTo(reference);
  }

  @Test
  void keyPartsAreNotSimplyConcatenated() {
    assertThat(SeededRandom.derive(42, "AB", "C").nextLong())
        .isNotEqualTo(SeededRandom.derive(42, "A", "BC").nextLong());
  }
}
