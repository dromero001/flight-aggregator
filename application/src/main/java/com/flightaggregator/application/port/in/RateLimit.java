package com.flightaggregator.application.port.in;

import java.time.Duration;

/**
 * At most {@code requests} of one action from one client address in each fixed window (Q9), at
 * least one. The window is at least 1 ms, the counter's unit: a shorter one would expire each
 * bucket at once.
 */
public record RateLimit(int requests, Duration window) {

  public RateLimit {
    if (requests < 1) {
      throw new IllegalArgumentException(
          "A rate limit must admit at least 1 request, not " + requests);
    }
    if (window == null) {
      throw new IllegalArgumentException("A rate limit needs a window");
    }
    if (window.toMillis() < 1) {
      throw new IllegalArgumentException(
          "A rate limit's window must be 1 ms or more, not " + window);
    }
  }
}
