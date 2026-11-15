package com.flightaggregator.application.port.in;

/** The admin's manual ingestion trigger (§6.6; §6.8, "Run ingestion now"). */
public interface TriggerIngestion {

  /** Starts a run in the background, unless one is running already. */
  TriggerOutcome trigger();

  enum TriggerOutcome {
    STARTED,
    ALREADY_RUNNING
  }
}
