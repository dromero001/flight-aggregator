package com.flightaggregator.application.port.out;

/** Starts an ingestion run now, besides the scheduled ones (§6.6, §6.8 "Run ingestion now"). */
public interface IngestionLauncher {

  /**
   * Starts a run in the background under the ingestion's lock and returns once it holds the lock:
   * {@code true} when it started, {@code false} at once when another run holds the lock.
   */
  boolean launch();
}
