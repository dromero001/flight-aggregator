package com.flightaggregator.application.port.in;

/** Searches the published inventory, one keyset page at a time (§4, §5.4). */
public interface SearchItineraries {

  /**
   * The page the query asks for: the first, or the one after its cursor.
   *
   * @throws InvalidCursorException when the cursor continues a search in another sort order
   * @throws InventoryUnavailableException when the inventory cannot be read in time
   */
  SearchPage search(SearchQuery query);
}
