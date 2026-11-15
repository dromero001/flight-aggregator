package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.StoredPage;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A reader of the itineraries the test gives it, all in one generation. */
public final class InMemoryItineraryReader implements ItineraryReader {

  public static final long GENERATION = 3;

  private final List<Itinerary> itineraries = new ArrayList<>();

  public InMemoryItineraryReader with(Itinerary... added) {
    itineraries.addAll(List.of(added));
    return this;
  }

  @Override
  public InventoryState state() {
    throw new UnsupportedOperationException("This fake reads no state");
  }

  /**
   * The first page by price of the itineraries departing after {@code now} that match the origin
   * and destination asked for; ties keep the order they were given in. Any other filter, order or
   * seek position fails the test.
   */
  @Override
  public StoredPage page(
      SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {
    if (criteria.sort() != SortOrder.PRICE
        || criteria.maxPrice().isPresent()
        || criteria.carrier().isPresent()
        || after.isPresent()) {
      throw new UnsupportedOperationException(
          "This fake reads only first pages by route: " + criteria);
    }
    List<Itinerary> page =
        itineraries.stream()
            .filter(itinerary -> itinerary.isSellable(now))
            .filter(itinerary -> criteria.origin().map(itinerary.origin()::equals).orElse(true))
            .filter(
                itinerary ->
                    criteria.destination().map(itinerary.destination()::equals).orElse(true))
            .sorted(Comparator.comparing(Itinerary::price))
            .limit(limit)
            .toList();
    return new StoredPage(GENERATION, page);
  }

  @Override
  public Optional<Itinerary> byId(ItineraryId id) {
    return itineraries.stream().filter(itinerary -> itinerary.id().equals(id)).findFirst();
  }
}
