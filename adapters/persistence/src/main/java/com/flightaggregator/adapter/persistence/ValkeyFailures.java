package com.flightaggregator.adapter.persistence;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * How the Valkey stores fail open (§6.2): a command Valkey fails, by being down, unreachable or too
 * slow, gives the store's fallback instead of an exception, so search keeps working, only slower.
 * Spring Data Redis translates every client failure into a {@link DataAccessException}; anything
 * else is a bug and is thrown.
 *
 * <p>An outage costs one command timeout, not one per command (Review Focus 3): a failed command
 * opens a circuit breaker, and while it is open every command gets its fallback at once, without
 * being sent. When the skip has passed, one call probes Valkey, and its answer closes the breaker.
 * Until Valkey has answered once, one call at a time tries it too, so callers do not queue behind
 * each other's connection attempts. One instance is shared by the stores, because an outage is
 * Valkey's, not one store's.
 *
 * <p>An outage is two lines, however many commands fail meanwhile: its first failure is one {@code
 * WARN}, with the failure, and the breaker closing after it is one {@code INFO}.
 */
public final class ValkeyFailures {

  private static final Logger LOG = LoggerFactory.getLogger(ValkeyFailures.class);

  private final CircuitBreaker breaker;

  /** Whether an outage has been warned of and has not ended yet. */
  private final AtomicBoolean outage = new AtomicBoolean();

  /** Sends each command through {@code breaker}, built from {@link #circuitBreakerConfig}. */
  public ValkeyFailures(CircuitBreaker breaker) {
    this.breaker = breaker;
    breaker.getEventPublisher().onStateTransition(this::logTheEndOfAnOutage);
  }

  /**
   * Valkey's breaker (plan 5, the user's decision of 2026-11-15): one failed command opens it, it
   * skips Valkey for {@code skip}, then lets one call probe it. It starts as if probing, so the
   * first call goes alone as well. Only Valkey's failures count against it.
   */
  public static CircuitBreakerConfig circuitBreakerConfig(Duration skip) {
    return CircuitBreakerConfig.custom()
        .slidingWindow(1, 1, SlidingWindowType.COUNT_BASED)
        .failureRateThreshold(100)
        .waitDurationInOpenState(skip)
        .permittedNumberOfCallsInHalfOpenState(1)
        .initialState(CircuitBreaker.State.HALF_OPEN)
        .recordExceptions(DataAccessException.class)
        .build();
  }

  /** The command's result, or the fallback's when Valkey fails the command or is being skipped. */
  <T> T call(Supplier<T> command, Supplier<T> fallback) {
    // Not executeSupplier: its acquirePermission lets a call through a closed breaker that another
    // thread has decided to open but not opened yet, so in a burst a caller whose command has just
    // failed could send its next one and pay a second timeout. tryAcquirePermission refuses it.
    if (!breaker.tryAcquirePermission()) {
      return fallback.get();
    }
    long started = breaker.getCurrentTimestamp();
    try {
      T result = command.get();
      breaker.onSuccess(since(started), breaker.getTimestampUnit());
      return result;
    } catch (DataAccessException e) {
      breaker.onError(since(started), breaker.getTimestampUnit(), e);
      warnOfAnOutage(e);
      return fallback.get();
    } catch (RuntimeException e) {
      // Not Valkey's failure, so its breaker takes it for an answer; the bug is thrown.
      breaker.onError(since(started), breaker.getTimestampUnit(), e);
      throw e;
    }
  }

  /** Runs the command; when Valkey fails it or is being skipped, it is dropped. */
  void run(Runnable command) {
    call(
        () -> {
          command.run();
          return null;
        },
        () -> null);
  }

  private long since(long started) {
    return breaker.getCurrentTimestamp() - started;
  }

  /** Each failure opens the breaker, so the first since it last closed starts an outage. */
  private void warnOfAnOutage(DataAccessException failure) {
    if (outage.compareAndSet(false, true)) {
      LOG.atWarn()
          .setCause(failure)
          .log(
              "Valkey is unavailable: searches run without the result cache, refresh claims and"
                  + " request counts until it answers again");
    }
  }

  /** The breaker closes when a call Valkey answers, the first one or a probe, ends its trial. */
  private void logTheEndOfAnOutage(CircuitBreakerOnStateTransitionEvent transition) {
    if (transition.getStateTransition().getToState() == CircuitBreaker.State.CLOSED
        && outage.compareAndSet(true, false)) {
      LOG.atInfo().log("Valkey answers again");
    }
  }
}
