package com.flightaggregator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.service.fakes.InMemoryRequestCounter;
import java.time.Duration;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

  private static final String CLIENT = "203.0.113.9";

  /** Small limits, so that a test reaches them in a few requests. */
  private static final Map<RateLimitedAction, RateLimit> LIMITS =
      Map.of(
          RateLimitedAction.SEARCH, new RateLimit(3, Duration.ofMinutes(1)),
          RateLimitedAction.REPRICE, new RateLimit(2, Duration.ofMinutes(1)));

  private final InMemoryRequestCounter counter = new InMemoryRequestCounter();
  private final RateLimitService limits = new RateLimitService(counter, LIMITS);

  /**
   * Every rate-limited action has a limit, or the service is not built: the application stops at
   * startup, naming the action, instead of failing its first request (G1, the user's decision of
   * 2026-11-16).
   */
  @Test
  void refusesLimitsThatLeaveAnActionOut() {
    Map<RateLimitedAction, RateLimit> searchOnly =
        Map.of(RateLimitedAction.SEARCH, new RateLimit(3, Duration.ofMinutes(1)));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimitService(counter, searchOnly))
        .withMessage("Every rate-limited action needs a limit; none is set for [REPRICE]");
  }

  @Test
  void admitsUpToTheLimitInAWindow() {
    assertThat(IntStream.range(0, 3).mapToObj(i -> limits.admit(CLIENT, RateLimitedAction.SEARCH)))
        .containsOnly(new Admission.Admitted());
  }

  /** Q9: the client may retry when its window ends. */
  @Test
  void rejectsPastItWithTheTimeLeft() {
    counter.resetsIn(Duration.ofSeconds(42));
    IntStream.range(0, 3).forEach(i -> limits.admit(CLIENT, RateLimitedAction.SEARCH));

    assertThat(limits.admit(CLIENT, RateLimitedAction.SEARCH))
        .isEqualTo(new Admission.Rejected(Duration.ofSeconds(42)));
  }

  /** Q9: re-price has its own counter, and its own limit, so searching never spends it. */
  @Test
  void countsSearchAndRepriceApart() {
    IntStream.range(0, 3).forEach(i -> limits.admit(CLIENT, RateLimitedAction.SEARCH));

    assertThat(IntStream.range(0, 3).mapToObj(i -> limits.admit(CLIENT, RateLimitedAction.REPRICE)))
        .containsExactly(
            new Admission.Admitted(),
            new Admission.Admitted(),
            new Admission.Rejected(Duration.ofSeconds(30)));
  }

  /** §6.2: without Valkey the system keeps working, so a request nobody can count goes on. */
  @Test
  void admitsWhenTheCounterIsUnavailable() {
    counter.becomeUnavailable();

    assertThat(IntStream.range(0, 5).mapToObj(i -> limits.admit(CLIENT, RateLimitedAction.REPRICE)))
        .containsOnly(new Admission.Admitted());
  }
}
