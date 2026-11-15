package com.flightaggregator.adapter.persistence;

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
 * <p>An outage is two lines, however many commands fail meanwhile: the first failure while Valkey
 * was answering is one {@code WARN}, with the failure, and the first answer after it one {@code
 * INFO}. One instance is shared by the stores, because an outage is Valkey's, not one store's.
 */
public final class ValkeyFailures {

  private static final Logger LOG = LoggerFactory.getLogger(ValkeyFailures.class);

  /** Whether the last command Valkey was sent answered; it is assumed to until one fails. */
  private final AtomicBoolean answering = new AtomicBoolean(true);

  /** The command's result, or the fallback's when Valkey fails the command. */
  <T> T call(Supplier<T> command, Supplier<T> fallback) {
    T result;
    try {
      result = command.get();
    } catch (DataAccessException e) {
      if (answering.compareAndSet(true, false)) {
        LOG.atWarn()
            .setCause(e)
            .log(
                "Valkey is unavailable: searches run without the result cache, refresh claims"
                    + " and request counts until it answers again");
      }
      return fallback.get();
    }
    if (answering.compareAndSet(false, true)) {
      LOG.atInfo().log("Valkey answers again");
    }
    return result;
  }

  /** Runs the command; when Valkey fails it, it is dropped. */
  void run(Runnable command) {
    call(
        () -> {
          command.run();
          return null;
        },
        () -> null);
  }
}
