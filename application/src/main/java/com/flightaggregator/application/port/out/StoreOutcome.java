package com.flightaggregator.application.port.out;

/** What became of a re-quote written back in place (Q5). */
public enum StoreOutcome {
  /** The active generation's row of the itinerary now holds the re-quote. */
  STORED,
  /**
   * The active generation no longer holds the row: after the itinerary was read, a run replaced the
   * inventory, or a refresh rewrote its origin's itineraries without it; or another re-price of the
   * same itinerary rewrote it at the same moment. Nothing was written.
   */
  GONE,
  /**
   * An ingestion run holds the inventory to replace it, or a stale-route refresh to rewrite its
   * origin's itineraries, so nothing was written, without waiting for either; each publishes its
   * own download.
   */
  BUSY
}
