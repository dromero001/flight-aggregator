package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.SearchCriteria;
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
import com.flightaggregator.application.service.SearchItinerariesService;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The search use case SearchConfig declares, with the application's own settings, over a reader, a
 * cache, a coordination and a scheduler the test holds.
 */
class SearchConfigTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final Airport MADRID = new Airport("MAD");

  /** The first page of every search from Madrid, by price. */
  private static final SearchQuery FROM_MADRID =
      new SearchQuery(
          new SearchCriteria(
              Optional.of(MADRID),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              SortOrder.PRICE),
          20,
          Optional.empty());

  /** When the inventory the reader serves was published. */
  private Instant publishedAt = NOW;

  /** Every origin whose refresh was requested, in order. */
  private final List<Airport> requested = new ArrayList<>();

  /** How many times the airport catalogue was read. */
  private final AtomicInteger catalogueReads = new AtomicInteger();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(SearchConfig.class)
          .withBean(ItineraryReader.class, () -> new Published())
          .withBean(SearchCache.class, NothingCached::new)
          .withBean(
              AirportCatalogue.class,
              () ->
                  () -> {
                    catalogueReads.incrementAndGet();
                    return Set.of(MADRID);
                  })
          .withBean(RefreshCoordination.class, EveryClaimGranted::new)
          .withBean("backgroundRefresh", RefreshScheduler.class, () -> requested::add)
          .withBean(Clock.class, () -> () -> NOW);

  @Test
  void declaresTheSearchUseCase() {
    runner.run(
        context ->
            assertThat(context)
                .getBean(SearchItineraries.class)
                .isInstanceOf(SearchItinerariesService.class));
  }

  /**
   * §6.4: with the application's settings, a search from an origin published 10 min ago asks for no
   * refresh, and one published a second longer ago asks for one.
   */
  @ParameterizedTest
  @CsvSource({"PT10M, 0", "PT10M1S, 1"})
  void asksToRefreshAnOriginOnlyOnceItIsMoreThanTenMinutesOld(Duration age, int refreshes) {
    publishedAt = NOW.minus(age);

    runner.run(
        context -> {
          context.getBean(SearchItineraries.class).search(FROM_MADRID);

          assertThat(requested).hasSize(refreshes);
        });
  }

  /**
   * The catalogue is migration V2's fixed set of airports, and its store reads the table on every
   * call, so the wired use case reads it once, not once per search with an origin.
   */
  @Test
  void readsTheAirportCatalogueOnce() {
    runner.run(
        context -> {
          SearchItineraries search = context.getBean(SearchItineraries.class);

          search.search(FROM_MADRID);
          search.search(FROM_MADRID);

          assertThat(catalogueReads).hasValue(1);
        });
  }

  /** An inventory of no rows, published at {@code publishedAt}. */
  private final class Published implements ItineraryReader {

    @Override
    public InventoryState state() {
      return new InventoryState(1, Optional.of(publishedAt));
    }

    @Override
    public StoredPage page(
        SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {
      return new StoredPage(1, List.of());
    }

    @Override
    public Optional<Itinerary> byId(ItineraryId id) {
      throw new UnsupportedOperationException();
    }
  }

  /** A cache that keeps nothing. */
  private static final class NothingCached implements SearchCache {

    @Override
    public Optional<SearchPage> get(SearchKey key) {
      return Optional.empty();
    }

    @Override
    public void put(SearchKey key, SearchPage page) {}
  }

  /** No origin was refreshed, and every claim is granted. */
  private static final class EveryClaimGranted implements RefreshCoordination {

    @Override
    public Optional<Instant> refreshedAt(Airport origin) {
      return Optional.empty();
    }

    @Override
    public boolean claim(Airport origin) {
      return true;
    }

    @Override
    public void refreshed(Airport origin, Instant at) {
      throw new UnsupportedOperationException();
    }
  }
}
