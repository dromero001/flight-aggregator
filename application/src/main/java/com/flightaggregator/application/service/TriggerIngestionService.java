package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.TriggerIngestion;
import com.flightaggregator.application.port.out.IngestionLauncher;

/**
 * Starts a run through the launcher, which takes the scheduled runs' lock, so a manual run never
 * overlaps another (§6.2).
 */
public final class TriggerIngestionService implements TriggerIngestion {

  private final IngestionLauncher launcher;

  public TriggerIngestionService(IngestionLauncher launcher) {
    this.launcher = launcher;
  }

  @Override
  public TriggerOutcome trigger() {
    return launcher.launch() ? TriggerOutcome.STARTED : TriggerOutcome.ALREADY_RUNNING;
  }
}
