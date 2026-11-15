package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.InvalidCursorException;
import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SearchItineraries;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SearchQuery;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.port.out.SearchKey;
import com.flightaggregator.application.port.out.StoredPage;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Searches the published inventory a keyset page at a time (§5.4). A page is read with one row more
 * than it holds, so the next page's cursor is given only when more rows exist; the cursor carries
 * the page's generation, and a cursor of another generation still seeks from its position, now in
 * the active generation, with the page flagged as changed (Q1). Only first pages of searches
 * without Max Price are cached, under the active generation (Q3, §6.2); a page read from a newer
 * generation than that, when a run publishes between the two reads, is served but not cached. A
 * cached page that has lost any row to departure since it was cached is a miss, read again and
 * cached again, so no page is served with a departed flight, shorter or empty (§4.1). The cache
 * keeps no page past its first departure, so this is a safety net, for a cache that does not, or
 * for instances whose clocks disagree.
 *
 * <p>A search from an origin in the airport catalogue then asks for that origin's background
 * refresh when its last publication or refresh, whichever is later, is more than the freshness
 * threshold old, and this instance wins the origin's claim (§6.4, §6.2); an origin the system does
 * not know is never refreshed, so no client can have the providers download made-up origins. It
 * comes after the page is ready, so a search that fails to read claims nothing, and it never fails
 * or holds up the search beyond the ports' own bounds: the coordination fails open (§6.2), and
 * {@link RefreshScheduler#request} returns at once without throwing. Before the first publication
 * nothing is refreshed: the first run is loading every origin.
 */
public final class SearchItinerariesService implements SearchItineraries {

  private final ItineraryReader reader;
  private final SearchCache cache;
  private final AirportCatalogue airports;
  private final RefreshCoordination coordination;
  private final RefreshScheduler refreshes;
  private final Clock clock;
  private final Duration freshnessThreshold;

  public SearchItinerariesService(
      ItineraryReader reader,
      SearchCache cache,
      AirportCatalogue airports,
      RefreshCoordination coordination,
      RefreshScheduler refreshes,
      Clock clock,
      Duration freshnessThreshold) {
    this.reader = reader;
    this.cache = cache;
    this.airports = airports;
    this.coordination = coordination;
    this.refreshes = refreshes;
    this.clock = clock;
    this.freshnessThreshold = freshnessThreshold;
  }

  @Override
  public SearchPage search(SearchQuery query) {
    refuseACursorOfAnotherSortOrder(query);
    Instant now = clock.now();
    InventoryState state = reader.state();
    SearchPage page =
        isCached(query)
            ? cachedOrRead(query, state.generation(), now)
            : pageOf(query, read(query, now));
    query.criteria().origin().ifPresent(origin -> requestRefreshIfStale(origin, state, now));
    return page;
  }

  /** A cursor's position is in its search's sort order, so under another order it means nothing. */
  private static void refuseACursorOfAnotherSortOrder(SearchQuery query) {
    SortOrder sort = query.criteria().sort();
    Optional<SortOrder> cursorSort = query.cursor().map(PageCursor::sort);
    if (cursorSort.isPresent() && cursorSort.get() != sort) {
      throw new InvalidCursorException(
          "The cursor continues a search sorted by " + cursorSort.get() + ", not by " + sort);
    }
  }

  /** Q3: only first pages of searches without Max Price. */
  private static boolean isCached(SearchQuery query) {
    return query.cursor().isEmpty() && query.criteria().maxPrice().isEmpty();
  }

  private SearchPage cachedOrRead(SearchQuery query, long generation, Instant now) {
    SearchKey key = new SearchKey(generation, query.criteria(), query.limit());
    Optional<SearchPage> cached = cache.get(key).filter(page -> isAllSellable(page, now));
    if (cached.isPresent()) {
      return cached.get();
    }
    StoredPage stored = read(query, now);
    SearchPage page = pageOf(query, stored);
    if (stored.generation() == generation) {
      cache.put(key, page);
    }
    return page;
  }

  /**
   * §4.1, the user's decision: a cached page that has lost any row to departure since it was cached
   * is a miss, read again and cached again, so a page is never served shorter or empty.
   */
  private static boolean isAllSellable(SearchPage cached, Instant now) {
    return cached.itineraries().stream().allMatch(itinerary -> itinerary.isSellable(now));
  }

  private StoredPage read(SearchQuery query, Instant now) {
    return reader.page(
        query.criteria(), query.cursor().map(PageCursor::position), query.limit() + 1, now);
  }

  private static SearchPage pageOf(SearchQuery query, StoredPage stored) {
    SortOrder sort = query.criteria().sort();
    boolean inventoryChanged =
        query.cursor().filter(cursor -> cursor.generation() != stored.generation()).isPresent();
    if (stored.itineraries().size() <= query.limit()) {
      return new SearchPage(stored.itineraries(), Optional.empty(), inventoryChanged);
    }
    List<Itinerary> page = stored.itineraries().subList(0, query.limit());
    PageCursor next = new PageCursor(stored.generation(), sort, SeekPosition.of(page.getLast()));
    return new SearchPage(page, Optional.of(next), inventoryChanged);
  }

  private void requestRefreshIfStale(Airport origin, InventoryState state, Instant now) {
    if (airports.airports().contains(origin)
        && isStale(origin, state, now)
        && coordination.claim(origin)) {
      refreshes.request(origin);
    }
  }

  private boolean isStale(Airport origin, InventoryState state, Instant now) {
    if (state.publishedAt().isEmpty()) {
      return false;
    }
    Instant published = state.publishedAt().get();
    Instant lastFresh =
        coordination.refreshedAt(origin).filter(published::isBefore).orElse(published);
    return Duration.between(lastFresh, now).compareTo(freshnessThreshold) > 0;
  }
}
