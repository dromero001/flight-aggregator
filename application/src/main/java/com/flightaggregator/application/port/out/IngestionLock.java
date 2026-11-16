package com.flightaggregator.application.port.out;

/**
 * The ingestion's lock (§6.2), which a run, scheduled or on demand, holds on whichever instance
 * runs it, until it ends.
 */
public interface IngestionLock {

  /** Whether a run holds the lock now, on any instance: an ingestion is going on. */
  boolean isHeld();
}
