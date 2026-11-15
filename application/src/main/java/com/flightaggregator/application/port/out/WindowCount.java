package com.flightaggregator.application.port.out;

import java.time.Duration;

/**
 * How many requests a bucket has counted in its current window, this one included, and how long
 * until the window ends and the count starts again.
 */
public record WindowCount(long count, Duration resetsIn) {

  /**
   * The count when the counter could not count: 0, which no increment returns, so it stays under
   * every limit and the request is admitted (§6.2).
   */
  public static WindowCount unavailable() {
    return new WindowCount(0, Duration.ZERO);
  }
}
