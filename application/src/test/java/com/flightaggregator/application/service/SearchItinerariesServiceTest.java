package com.flightaggregator.application.service;

import static com.flightaggregator.application.service.Legs.leg;
import static com.flightaggregator.domain.Provider.ALPHA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.flightaggregator.application.port.in.InvalidCursorException;
import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SearchQuery;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.SearchKey;
import com.flightaggregator.application.service.fakes.InMemoryRefreshCoordination;
import com.flightaggregator.application.service.fakes.InMemorySearchCache;
import com.flightaggregator.application.service.fakes.ScriptedItineraryReader;
import com.flightaggregator.application.service.fakes.ScriptedItineraryReader.PageAsked;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchItinerariesServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final Duration THRESHOLD = Duration.ofMinutes(10);
  private static final long GENERATION = 7;

  private static final Airport MADRID = new Airport("MAD");

  /** Every search, no filter, by price. */
  private static final SearchCriteria ANYWHERE =
      new SearchCriteria(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), SortOrder.PRICE);

  /** Every search from Madrid, by price. */
  private static final SearchCriteria FROM_MADRID =
      new SearchCriteria(
          Optional.of(MADRID),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          SortOrder.PRICE);

  /** Every search to Madrid, from anywhere, by price. */
  private static final SearchCriteria TO_MADRID =
      new SearchCriteria(
          Optional.empty(),
          Optional.of(MADRID),
          Optional.empty(),
          Optional.empty(),
          SortOrder.PRICE);

  /** Every search from Madrid up to 200 euros, by price: never cached (Q3). */
  private static final SearchCriteria FROM_MADRID_UP_TO_200 =
      new SearchCriteria(
          Optional.of(MADRID),
          Optional.empty(),
          Optional.of(Money.euros("200.00")),
          Optional.empty(),
          SortOrder.PRICE);

  /** Three direct flights from Madrid to London, in the order the reader returns them. */
  private static final Itinerary FIRST = trip(1);

  private static final Itinerary SECOND = trip(2);
  private static final Itinerary THIRD = trip(3);

  /** The inventory, published a minute ago, so no origin in it is stale. */
  private final ScriptedItineraryReader reader =
      new ScriptedItineraryReader(GENERATION, NOW.minus(Duration.ofMinutes(1)));

  private final InMemorySearchCache cache = new InMemorySearchCache();
  private final InMemoryRefreshCoordination coordination = new InMemoryRefreshCoordination();

  /** Every origin whose refresh was requested, in order. */
  private final List<Airport> requested = new ArrayList<>();

  private final Clock clock = () -> NOW;

  private SearchItinerariesService service() {
    return new SearchItinerariesService(
        reader, cache, coordination, requested::add, clock, THRESHOLD);
  }

  private static Itinerary trip(int flightNumber) {
    return new Itinerary(List.of(leg(ALPHA, flightNumber, "MAD", "LHR")));
  }

  private static SearchQuery firstPage(SearchCriteria criteria, int limit) {
    return new SearchQuery(criteria, limit, Optional.empty());
  }

  /**
   * A row beyond the page means a next page, whose cursor points at the page's last row, in the
   * page's generation and sort order.
   */
  @Test
  void returnsAPageWithACursorWhenMoreRowsExist() {
    reader.holding(FIRST, SECOND, THIRD);

    SearchPage page = service().search(firstPage(ANYWHERE, 2));

    assertThat(page)
        .isEqualTo(
            new SearchPage(
                List.of(FIRST, SECOND),
                Optional.of(new PageCursor(GENERATION, SortOrder.PRICE, SeekPosition.of(SECOND))),
                false));
  }

  /** A page that holds the last rows exactly has no next page to point to. */
  @Test
  void returnsNoCursorOnTheLastPage() {
    reader.holding(FIRST, SECOND);

    SearchPage page = service().search(firstPage(ANYWHERE, 2));

    assertThat(page).isEqualTo(new SearchPage(List.of(FIRST, SECOND), Optional.empty(), false));
  }

  /**
   * Q1: a cursor of a replaced generation still seeks from its position, now in the active
   * generation, and the page says the inventory changed; a cursor of the active one does not.
   */
  @Test
  void flagsACursorOfAnotherGeneration() {
    reader.holding(SECOND, THIRD);
    PageCursor ofTheLastGeneration =
        new PageCursor(GENERATION - 1, SortOrder.PRICE, SeekPosition.of(FIRST));
    PageCursor ofThisGeneration =
        new PageCursor(GENERATION, SortOrder.PRICE, SeekPosition.of(FIRST));

    SearchPage flagged =
        service().search(new SearchQuery(ANYWHERE, 2, Optional.of(ofTheLastGeneration)));
    SearchPage notFlagged =
        service().search(new SearchQuery(ANYWHERE, 2, Optional.of(ofThisGeneration)));

    assertThat(flagged).isEqualTo(new SearchPage(List.of(SECOND, THIRD), Optional.empty(), true));
    assertThat(notFlagged)
        .isEqualTo(new SearchPage(List.of(SECOND, THIRD), Optional.empty(), false));
    assertThat(reader.pagesAsked())
        .extracting(PageAsked::after)
        .containsExactly(Optional.of(SeekPosition.of(FIRST)), Optional.of(SeekPosition.of(FIRST)));
  }

  /** A cursor's position is in its search's sort order, so under another order it means nothing. */
  @Test
  void rejectsACursorOfAnotherSortOrder() {
    reader.holding(SECOND, THIRD);
    PageCursor byPrice = new PageCursor(GENERATION, SortOrder.PRICE, SeekPosition.of(FIRST));
    SearchCriteria byDeparture =
        new SearchCriteria(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            SortOrder.DEPARTURE);

    assertThatThrownBy(
            () -> service().search(new SearchQuery(byDeparture, 2, Optional.of(byPrice))))
        .isInstanceOf(InvalidCursorException.class);
  }

  /** §6.2: a first page cached under the active generation is served, and PostgreSQL not read. */
  @Test
  void servesACachedFirstPageWithoutReading() {
    reader.holding(FIRST, SECOND, THIRD);
    SearchPage cached = new SearchPage(List.of(THIRD), Optional.empty(), false);
    cache.put(new SearchKey(GENERATION, ANYWHERE, 2), cached);

    SearchPage page = service().search(firstPage(ANYWHERE, 2));

    assertThat(page).isEqualTo(cached);
    assertThat(reader.pagesAsked()).isEmpty();
  }

  /**
   * Q3: only first pages without Max Price are cached, and only they are looked up. A page after a
   * cursor shares its first page's key, and a page with Max Price may find one under its own, yet
   * each is read from PostgreSQL and not cached.
   */
  @Test
  void cachesOnlyFirstPagesWithoutMaxPrice() {
    SearchPage cachedUpTo200 = new SearchPage(List.of(FIRST), Optional.empty(), false);
    cache.put(new SearchKey(GENERATION, FROM_MADRID_UP_TO_200, 2), cachedUpTo200);
    reader.holding(FIRST, SECOND, THIRD);
    SearchPage first = service().search(firstPage(FROM_MADRID, 2));
    reader.holding(THIRD);

    SearchPage next = service().search(new SearchQuery(FROM_MADRID, 2, first.next()));
    SearchPage withMaxPrice = service().search(firstPage(FROM_MADRID_UP_TO_200, 2));

    SearchPage lastRow = new SearchPage(List.of(THIRD), Optional.empty(), false);
    assertThat(next).isEqualTo(lastRow);
    assertThat(withMaxPrice).isEqualTo(lastRow);
    assertThat(cache.pages())
        .containsOnly(
            entry(new SearchKey(GENERATION, FROM_MADRID, 2), first),
            entry(new SearchKey(GENERATION, FROM_MADRID_UP_TO_200, 2), cachedUpTo200));
  }

  /**
   * A run that publishes between reading the generation and reading the page leaves a page of the
   * new generation: it is served, but not cached under the generation read before it.
   */
  @Test
  void doesNotCacheAPageOfAnotherGeneration() {
    reader.holding(FIRST, SECOND, THIRD).readingPagesOf(GENERATION + 1);

    SearchPage page = service().search(firstPage(ANYWHERE, 2));

    assertThat(page)
        .isEqualTo(
            new SearchPage(
                List.of(FIRST, SECOND),
                Optional.of(
                    new PageCursor(GENERATION + 1, SortOrder.PRICE, SeekPosition.of(SECOND))),
                false));
    assertThat(cache.pages()).isEmpty();
  }

  /**
   * §6.4: a search on an origin last published 11 min ago asks once for its refresh, whether its
   * page is served from the cache or read; a second search finds the origin claimed (§6.2) and asks
   * for nothing.
   */
  @ParameterizedTest(name = "page served from the cache: {0}")
  @ValueSource(booleans = {true, false})
  void requestsOneRefreshForAStaleOrigin(boolean servedFromTheCache) {
    reader.published(GENERATION, NOW.minus(Duration.ofMinutes(11))).holding(FIRST);
    cache.put(
        new SearchKey(GENERATION, FROM_MADRID, 2),
        new SearchPage(List.of(FIRST), Optional.empty(), false));
    SearchQuery query = firstPage(servedFromTheCache ? FROM_MADRID : FROM_MADRID_UP_TO_200, 2);

    service().search(query);
    assertThat(requested).containsExactly(MADRID);

    service().search(query);
    assertThat(requested).containsExactly(MADRID);
  }

  static Stream<Arguments> freshOriginsAndSearchesWithoutOrigin() {
    Optional<Duration> neverRefreshed = Optional.empty();
    return Stream.of(
        argumentSet(
            "published 11 min ago, refreshed 5 min ago",
            FROM_MADRID,
            Duration.ofMinutes(11),
            Optional.of(Duration.ofMinutes(5))),
        argumentSet(
            "published 1 min ago, refreshed 11 min ago",
            FROM_MADRID,
            Duration.ofMinutes(1),
            Optional.of(Duration.ofMinutes(11))),
        argumentSet(
            "published exactly 10 min ago", FROM_MADRID, Duration.ofMinutes(10), neverRefreshed),
        argumentSet(
            "to Madrid from anywhere, published 11 min ago",
            TO_MADRID,
            Duration.ofMinutes(11),
            neverRefreshed));
  }

  /**
   * §6.4: an origin is stale only once its last publication or refresh, whichever is later, is more
   * than the threshold old; a search without an origin refreshes nothing. Neither claims the
   * origin, so a claim never stands in the way of a refresh that becomes due.
   */
  @ParameterizedTest
  @MethodSource("freshOriginsAndSearchesWithoutOrigin")
  void doesNotRefreshAFreshOriginOrASearchWithoutOrigin(
      SearchCriteria criteria, Duration publishedAgo, Optional<Duration> refreshedAgo) {
    reader.published(GENERATION, NOW.minus(publishedAgo)).holding(FIRST);
    refreshedAgo.ifPresent(ago -> coordination.refreshed(MADRID, NOW.minus(ago)));

    service().search(firstPage(criteria, 2));

    assertThat(requested).isEmpty();
    assertThat(coordination.claimed()).isEmpty();
  }

  /**
   * Before the first run publishes, there is nothing to measure an origin's age from, nor anything
   * to refresh: the search answers an empty page and asks for no refresh.
   */
  @Test
  void doesNotRefreshBeforeTheFirstPublication() {
    reader.beforeTheFirstPublication();

    SearchPage page = service().search(firstPage(FROM_MADRID, 2));

    assertThat(page).isEqualTo(new SearchPage(List.of(), Optional.empty(), false));
    assertThat(requested).isEmpty();
    assertThat(coordination.claimed()).isEmpty();
  }
}
