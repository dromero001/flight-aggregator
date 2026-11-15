package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An itinerary repository that keeps what the last writer of a load, or of a rewrite through an
 * origin, pushed, and publishes it only if the writer returned normally, as the real one does.
 */
public final class FakeItineraryRepository implements ItineraryRepository {

  public static final long GENERATION = 7;
  public static final long ROWS_IN_DEFAULT_PARTITION = 2;
  public static final Instant PARTITIONED_UNTIL = Instant.parse("2026-12-28T00:00:00Z");

  private int calls;
  private List<Itinerary> published = List.of();
  private Optional<Airport> rewrittenThrough = Optional.empty();
  private List<Itinerary> rewritten = List.of();
  private final List<Itinerary> updated = new ArrayList<>();
  private StoreOutcome outcome = StoreOutcome.STORED;

  @Override
  public Publication replaceInventory(Instant now, ItineraryWriter writer) {
    calls++;
    List<Itinerary> written = new ArrayList<>();
    writer.writeTo(written::add);
    published = List.copyOf(written);
    return new Publication(
        GENERATION, published.size(), ROWS_IN_DEFAULT_PARTITION, PARTITIONED_UNTIL);
  }

  /**
   * Keeps what the writer pushed as the itineraries through the origin, only if the writer returned
   * normally, as the real one does.
   */
  @Override
  public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
    List<Itinerary> written = new ArrayList<>();
    writer.writeTo(written::add);
    rewrittenThrough = Optional.of(origin);
    rewritten = List.copyOf(written);
    return new Publication(
        GENERATION, rewritten.size(), ROWS_IN_DEFAULT_PARTITION, PARTITIONED_UNTIL);
  }

  /** The origin of the last rewrite; empty when nothing was rewritten. */
  public Optional<Airport> rewrittenThrough() {
    return rewrittenThrough;
  }

  /** What the last rewrite's writer pushed. */
  public List<Itinerary> rewritten() {
    return rewritten;
  }

  /** Remembers the re-quote, and answers what {@link #answers} set: stored, by default. */
  @Override
  public synchronized StoreOutcome updateInPlace(Itinerary repriced) {
    updated.add(repriced);
    return outcome;
  }

  /** What every later update in place answers. */
  public synchronized void answers(StoreOutcome outcome) {
    this.outcome = outcome;
  }

  /** Every re-quote asked to be updated in place, in order, whatever it answered. */
  public synchronized List<Itinerary> updated() {
    return List.copyOf(updated);
  }

  public int calls() {
    return calls;
  }

  public List<Itinerary> published() {
    return published;
  }
}
