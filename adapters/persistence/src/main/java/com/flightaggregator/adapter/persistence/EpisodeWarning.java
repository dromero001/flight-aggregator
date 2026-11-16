package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * One {@code WARN} for a burst of like events, such as many cached values that cannot be read after
 * a change of their format (plan 5, an extra the user approved on 2026-11-15). The first event logs
 * it and starts an episode of a set length; the events until the episode has ended are only
 * counted, and the first one after it logs the {@code WARN} again, with that count as {@code
 * unloggedRepeats}. The count belongs to the episode before, so a count above zero comes with
 * {@code unloggedSince}, when that episode began.
 *
 * <p>Logback has no such filter to reuse: its {@code DuplicateMessageFilter} acts on every logger
 * at once, drops a message for good after a few repeats, and counts nothing.
 */
final class EpisodeWarning {

  private final Logger log;
  private final String message;
  private final Duration length;
  private final Clock clock;

  /** The current episode; the first event starts one, as the one before it has always ended. */
  private final AtomicReference<Episode> episode =
      new AtomicReference<>(new Episode(Instant.MIN, Instant.MIN, 0));

  EpisodeWarning(Logger log, String message, Duration length, Clock clock) {
    this.log = log;
    this.message = message;
    this.length = length;
    this.clock = clock;
  }

  /** Logs the {@code WARN} if no episode is going on, and otherwise counts the event. */
  void warn() {
    Instant now = clock.now();
    Episode before =
        episode.getAndUpdate(
            current ->
                current.isGoingOn(now)
                    ? current.withOneMoreUnlogged()
                    : new Episode(now, now.plus(length), 0));
    if (!before.isGoingOn(now)) {
      LoggingEventBuilder warning = log.atWarn().addKeyValue("unloggedRepeats", before.unlogged());
      if (before.unlogged() > 0) {
        warning = warning.addKeyValue("unloggedSince", before.began());
      }
      warning.log(message);
    }
  }

  /**
   * An episode begins at {@code began} and ends at {@code ends}; {@code unlogged} counts its events
   * after the first.
   */
  private record Episode(Instant began, Instant ends, long unlogged) {

    boolean isGoingOn(Instant now) {
      return now.isBefore(ends);
    }

    Episode withOneMoreUnlogged() {
      return new Episode(began, ends, unlogged + 1);
    }
  }
}
