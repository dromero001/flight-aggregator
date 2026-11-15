package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.StoredPage;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A reader that answers the state and the rows the test sets, and records each page it is asked
 * for. A page is the first {@code limit} of the rows, in the order the test gave them, whatever the
 * criteria and the seek position: filtering and seeking are the real reader's, so a test reads what
 * was asked from {@link #pagesAsked()}.
 */
public final class ScriptedItineraryReader implements ItineraryReader {

  /** One call of {@link #page}, with its arguments. */
  public record PageAsked(
      SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {}

  private InventoryState state;
  private long pageGeneration;
  private List<Itinerary> rows = List.of();
  private final List<PageAsked> asked = new ArrayList<>();

  public ScriptedItineraryReader(long generation, Instant publishedAt) {
    published(generation, publishedAt);
  }

  /** The inventory is now this generation, published at this time; its pages are read from it. */
  public ScriptedItineraryReader published(long generation, Instant at) {
    state = new InventoryState(generation, Optional.of(at));
    pageGeneration = generation;
    return this;
  }

  /** Nothing has been published yet: generation 0, with no publication time and no rows. */
  public ScriptedItineraryReader beforeTheFirstPublication() {
    state = new InventoryState(0, Optional.empty());
    pageGeneration = 0;
    rows = List.of();
    return this;
  }

  /**
   * Pages are read from this generation while the state still names the earlier one, as when a run
   * publishes between the two reads.
   */
  public ScriptedItineraryReader readingPagesOf(long generation) {
    pageGeneration = generation;
    return this;
  }

  /** The rows every page is read from, in the order the reader returns them. */
  public ScriptedItineraryReader holding(Itinerary... itineraries) {
    rows = List.of(itineraries);
    return this;
  }

  @Override
  public InventoryState state() {
    return state;
  }

  @Override
  public StoredPage page(
      SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {
    asked.add(new PageAsked(criteria, after, limit, now));
    return new StoredPage(pageGeneration, rows.stream().limit(limit).toList());
  }

  @Override
  public Optional<Itinerary> byId(ItineraryId id) {
    throw new UnsupportedOperationException("This fake reads only pages");
  }

  /** Every page asked for, in order. */
  public List<PageAsked> pagesAsked() {
    return List.copyOf(asked);
  }
}
