package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.InventoryStatus;
import com.flightaggregator.application.port.in.StatusReport;
import com.flightaggregator.application.port.in.TriggerIngestion;
import java.util.List;
import java.util.Optional;

/**
 * Test double of both admin use cases: answers the status and the trigger outcome the test sets
 * (nothing published and {@code STARTED} by default), and counts the triggers. Spring keeps one per
 * cached context, so each test starts with {@link #reset}.
 */
final class RecordingAdmin implements InventoryStatus, TriggerIngestion {

  private static final StatusReport NOTHING_YET =
      new StatusReport(0, Optional.empty(), Optional.empty(), false, List.of());

  private StatusReport status = NOTHING_YET;
  private TriggerOutcome outcome = TriggerOutcome.STARTED;
  private int triggers;

  void reset() {
    status = NOTHING_YET;
    outcome = TriggerOutcome.STARTED;
    triggers = 0;
  }

  void answer(StatusReport status) {
    this.status = status;
  }

  void answer(TriggerOutcome outcome) {
    this.outcome = outcome;
  }

  int triggers() {
    return triggers;
  }

  @Override
  public StatusReport status() {
    return status;
  }

  @Override
  public TriggerOutcome trigger() {
    triggers++;
    return outcome;
  }
}
