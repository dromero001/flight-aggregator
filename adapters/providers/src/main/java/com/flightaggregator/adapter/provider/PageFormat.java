package com.flightaggregator.adapter.provider;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.LegKey;
import java.util.Optional;

/**
 * How one provider pages a listing. The listing's query is the same for every provider ({@link
 * ListingQuery}); only the paging and the format differ.
 */
@FunctionalInterface
interface PageFormat {

  /** The request of the page at a position, empty for the first page, of the query's listing. */
  PageRequest page(ListingQuery query, Optional<String> position, int pageSize);

  /** The page at a position of one origin's legs, or of every leg when the origin is empty. */
  default PageRequest at(Optional<Airport> origin, Optional<String> position, int pageSize) {
    return page(ListingQuery.from(origin), position, pageSize);
  }

  /**
   * The quote of one flight: its listing, on one page with room for two records, so that a provider
   * holding two offers of the flight shows both on the page instead of signalling a next page.
   */
  default PageRequest quoting(LegKey flight) {
    return page(ListingQuery.of(flight), Optional.empty(), 2);
  }
}
