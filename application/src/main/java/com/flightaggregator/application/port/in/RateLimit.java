package com.flightaggregator.application.port.in;

import java.time.Duration;

/**
 * At most {@code requests} of one action from one client address in each fixed window (Q9). The
 * window is at least 1 ms, the counter's unit: a shorter one would expire each bucket at once.
 */
public record RateLimit(int requests, Duration window) {

  public RateLimit {
    if (window.toMillis() < 1) {
      throw new IllegalArgumentException(
          "A rate limit's window must be 1 ms or more, not " + window);
    }
  }
}
