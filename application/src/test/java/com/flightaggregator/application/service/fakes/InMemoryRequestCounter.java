package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.WindowCount;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Request counters in memory: each bucket counts for ever, and every count says that its window
 * ends after the time the test sets; or, made unavailable, it counts nothing, as Valkey down.
 */
public final class InMemoryRequestCounter implements RequestCounter {

  private final Map<String, Long> counts = new HashMap<>();
  private Duration resetsIn = Duration.ofSeconds(30);
  private boolean available = true;

  /** Every later count says its window ends after this. */
  public void resetsIn(Duration resetsIn) {
    this.resetsIn = resetsIn;
  }

  /** Every later increment answers {@link WindowCount#unavailable()}. */
  public void becomeUnavailable() {
    available = false;
  }

  @Override
  public WindowCount increment(String bucket, Duration window) {
    if (!available) {
      return WindowCount.unavailable();
    }
    return new WindowCount(counts.merge(bucket, 1L, Long::sum), resetsIn);
  }
}
