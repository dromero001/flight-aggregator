package com.flightaggregator.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WindowCountTest {

  /** No increment counts 0, so a count Valkey could not give stays under every limit (§6.2). */
  @Test
  void anUnavailableCountIsZeroAndResetsAtOnce() {
    assertThat(WindowCount.unavailable()).isEqualTo(new WindowCount(0, Duration.ZERO));
  }
}
