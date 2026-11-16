package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.WindowCount;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Q9's rate limits, read from what the use case RateLimitConfig declares admits, over a counter the
 * test holds: one count per bucket, and the window each request asked for.
 */
class RateLimitConfigTest {

  private static final String ADDRESS = "203.0.113.7";

  private final Map<String, Long> counts = new HashMap<>();
  private final List<Duration> windows = new ArrayList<>();

  private final RequestCounter counter =
      (bucket, window) -> {
        windows.add(window);
        return new WindowCount(counts.merge(bucket, 1L, Long::sum), window);
      };

  private final AdmitRequest admitRequest = new RateLimitConfig().admitRequest(counter);

  @Test
  void admitsSixtySearchesAMinuteFromOneAddress() {
    assertThat(answersTo(RateLimitedAction.SEARCH, 61)).isEqualTo(admittedThenRejected(60));
    assertThat(windows).containsOnly(Duration.ofMinutes(1));
  }

  @Test
  void admitsTenRepricesAMinuteFromOneAddress() {
    assertThat(answersTo(RateLimitedAction.REPRICE, 11)).isEqualTo(admittedThenRejected(10));
    assertThat(windows).containsOnly(Duration.ofMinutes(1));
  }

  private List<String> answersTo(RateLimitedAction action, int requests) {
    return IntStream.range(0, requests)
        .mapToObj(
            i ->
                admitRequest.admit(ADDRESS, action) instanceof Admission.Rejected
                    ? "rejected"
                    : "admitted")
        .toList();
  }

  private static List<String> admittedThenRejected(int admitted) {
    List<String> expected = new ArrayList<>(Collections.nCopies(admitted, "admitted"));
    expected.add("rejected");
    return expected;
  }
}
