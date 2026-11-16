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
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Q9's rate limits, read from what the use case RateLimitConfig declares admits, with the
 * application's own settings, over a counter the test holds: one count per bucket, and the window
 * each request asked for.
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

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(RateLimitConfig.class)
          .withBean(RequestCounter.class, () -> counter);

  @Test
  void admitsSixtySearchesAMinuteFromOneAddress() {
    runner.run(
        context -> {
          assertThat(answersTo(context, RateLimitedAction.SEARCH, 61))
              .isEqualTo(admittedThenRejected(60));
          assertThat(windows).containsOnly(Duration.ofMinutes(1));
        });
  }

  @Test
  void admitsTenRepricesAMinuteFromOneAddress() {
    runner.run(
        context -> {
          assertThat(answersTo(context, RateLimitedAction.REPRICE, 11))
              .isEqualTo(admittedThenRejected(10));
          assertThat(windows).containsOnly(Duration.ofMinutes(1));
        });
  }

  /** The limits are settings, so an operator tunes them without a build (G1, 2026-11-16). */
  @Test
  void readsEachLimitFromTheSettings() {
    runner
        .withPropertyValues(
            "rate-limit.actions.search.requests=3", "rate-limit.actions.search.window=PT10S")
        .run(
            context -> {
              assertThat(answersTo(context, RateLimitedAction.SEARCH, 4))
                  .isEqualTo(admittedThenRejected(3));
              assertThat(windows).containsOnly(Duration.ofSeconds(10));
            });
  }

  /**
   * Without a limit for every action, the application does not start, and says which action has
   * none, instead of failing that action's first request.
   */
  @Test
  void stopsStartupWithoutALimitForEachAction() {
    new ApplicationContextRunner()
        .withUserConfiguration(RateLimitConfig.class)
        .withBean(RequestCounter.class, () -> counter)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessage(
                        "Every rate-limited action needs a limit; none is set for [SEARCH,"
                            + " REPRICE]"));
  }

  private static List<String> answersTo(
      AssertableApplicationContext context, RateLimitedAction action, int requests) {
    AdmitRequest admitRequest = context.getBean(AdmitRequest.class);
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
