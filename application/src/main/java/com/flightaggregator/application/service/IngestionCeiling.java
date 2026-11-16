package com.flightaggregator.application.service;

import com.flightaggregator.domain.Itinerary;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A write would hold more itineraries than the configured ceiling allows (R7): a run's whole
 * inventory, or a stale-route refresh's itineraries through its origin. Thrown from inside the
 * repository's writer, so the write it stops is rolled back.
 */
final class IngestionCeiling extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private IngestionCeiling(long maxItineraries) {
    super("More than " + maxItineraries + " itineraries");
  }

  /**
   * Pushes each itinerary into the sink, and throws this exception at the first one past {@code
   * maxItineraries}, before pushing it. Answers how many were pushed. The stream is consumed with
   * {@code forEach} alone, so the builder pushes each itinerary as it is built (R4); an iterator
   * would buffer each origin's itineraries.
   */
  static long writeAtMost(
      long maxItineraries, Stream<Itinerary> itineraries, Consumer<Itinerary> sink) {
    AtomicLong written = new AtomicLong();
    itineraries.forEach(
        itinerary -> {
          if (written.incrementAndGet() > maxItineraries) {
            throw new IngestionCeiling(maxItineraries);
          }
          sink.accept(itinerary);
        });
    return written.get();
  }
}
