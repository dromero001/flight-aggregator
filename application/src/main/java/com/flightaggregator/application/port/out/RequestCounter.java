package com.flightaggregator.application.port.out;

import java.time.Duration;

/** Counts requests per bucket in fixed windows, shared by every instance. */
public interface RequestCounter {

  /**
   * Counts one request in the bucket's current window, which starts with its first request and
   * lasts {@code window}; {@link WindowCount#unavailable()} when the counter cannot count.
   */
  WindowCount increment(String bucket, Duration window);
}
