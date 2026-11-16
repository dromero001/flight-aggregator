package com.flightaggregator.application.port.in;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RateLimitTest {

  /**
   * A counter's window is a whole number of milliseconds: under one, the bucket expires at once,
   * every request counts 1 and the time left is negative.
   */
  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-1M", "PT0.0009S"})
  void refusesAWindowUnderAMillisecond(String window) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimit(60, Duration.parse(window)))
        .withMessageContaining("window");
  }

  /** One request a window is the smallest limit, and a valid one. */
  @Test
  void acceptsALimitOfOneRequest() {
    assertThatNoException().isThrownBy(() -> new RateLimit(1, Duration.ofMinutes(1)));
  }

  /** A limit set without its window, as a setting may be, is refused by name, not by a bare NPE. */
  @Test
  void refusesAMissingWindow() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimit(60, null))
        .withMessage("A rate limit needs a window");
  }

  /** A limit of no request at all would refuse every request: a setting's mistake, not a limit. */
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void refusesFewerThanOneRequest(int requests) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimit(requests, Duration.ofMinutes(1)))
        .withMessage("A rate limit must admit at least 1 request, not " + requests);
  }
}
