package com.flightaggregator.application.port.out;

/** What became of a re-quote written back in place (Q5). */
public enum StoreOutcome {
  /** The active generation's row of the itinerary now holds the re-quote. */
  STORED,
  /**
   * The active generation no longer holds the row: a run replaced the inventory after the itinerary
   * was read, or another re-price of the same itinerary rewrote it at the same moment. Nothing was
   * written.
   */
  GONE,
  /**
   * An ingestion run holds the inventory to replace it, so nothing was written, without waiting for
   * the run; the run publishes its own download.
   */
  BUSY
}
