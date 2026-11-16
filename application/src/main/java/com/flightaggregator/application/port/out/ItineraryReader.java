package com.flightaggregator.application.port.out;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads the itineraries of the active generation, the ones search serves. A read that outruns its
 * bound throws {@link com.flightaggregator.application.port.in.InventoryUnavailableException}.
 */
public interface ItineraryReader {

  /** The active generation, and when the last full run was published. */
  InventoryState state();

  /**
   * Up to {@code limit} itineraries departing after {@code now} that match the criteria, in their
   * sort order and then by id, starting after {@code after} when it is present; the generation and
   * the rows are read together, so the page is that generation's.
   */
  StoredPage page(SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now);

  /**
   * The itinerary with this id in the active generation, whatever its departure, so re-price can
   * still find a choice that has departed and offer its alternative (Q4).
   */
  Optional<Itinerary> byId(ItineraryId id);
}
