package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.ValkeyTestServer;
import com.flightaggregator.application.port.out.WindowCount;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValkeyRequestCounterTest {

  private static final String SEARCHES = "search:203.0.113.7";
  private static final String REPRICES = "reprice:203.0.113.7";

  /** Short, so that a test can wait for a window to end. */
  private static final Duration WINDOW = Duration.ofMillis(500);

  private final ValkeyRequestCounter counter =
      new ValkeyRequestCounter(ValkeyTestServer.valkey(), new ValkeyFailures());

  @BeforeEach
  void startFromAnEmptyValkey() {
    ValkeyTestServer.empty();
  }

  /**
   * A fixed window: it starts with the bucket's first request, a later request does not move its
   * end, and the count starts again once it is over. Each bucket counts on its own.
   */
  @Test
  void countsWithinAWindowAndResets() throws InterruptedException {
    WindowCount first = counter.increment(SEARCHES, WINDOW);
    Thread.sleep(200);
    WindowCount second = counter.increment(SEARCHES, WINDOW);
    WindowCount otherBucket = counter.increment(REPRICES, WINDOW);

    assertThat(first.count()).isEqualTo(1);
    assertThat(first.resetsIn()).isPositive().isLessThanOrEqualTo(WINDOW);
    assertThat(second.count()).isEqualTo(2);
    assertThat(second.resetsIn()).isPositive().isLessThanOrEqualTo(WINDOW.minusMillis(200));
    assertThat(otherBucket.count()).isEqualTo(1);

    Thread.sleep(second.resetsIn().plusMillis(200));

    assertThat(counter.increment(SEARCHES, WINDOW).count()).isEqualTo(1);
  }

  /** Valkey down, nothing is counted, so the request is admitted (§6.2). */
  @Test
  void countsNothingWhileValkeyIsDown() {
    counter.increment(SEARCHES, WINDOW);

    assertThat(ValkeyTestServer.whileDown(() -> counter.increment(SEARCHES, WINDOW)))
        .isEqualTo(WindowCount.unavailable());
  }
}
