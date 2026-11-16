package com.flightaggregator.bootstrap.refresh;

import com.flightaggregator.application.port.in.RefreshReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * The stale-route refresh's meters, with the outcome as their one tag, as a run's are (§6.10; plan
 * 5 Task 7's extra): {@code refresh.runs{outcome}} counts every refresh asked for, and {@code
 * refresh.duration{outcome}} times every one that was not skipped. The outcome is the report's,
 * {@code FAILED} for a refresh that ended with an exception, or {@code SKIPPED} for one that did
 * not start because another refresh was running (plan 5's closing round 1), which is counted but
 * not timed. Each outcome's meters are created by its first refresh, as the application's other
 * counters are.
 */
public final class RefreshMetrics {

  /** The outcome of a refresh that ended with an exception, beside the report's own outcomes. */
  private static final String FAILED = "FAILED";

  /** The outcome of a refresh skipped because another one runs: counted, never timed. */
  private static final String SKIPPED = "SKIPPED";

  private final MeterRegistry meters;

  public RefreshMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  /** A refresh that ended with its report, after {@code duration}. */
  public void record(RefreshReport report, Duration duration) {
    ended(report.outcome().name(), duration);
  }

  /** A refresh that ended with an exception instead of a report, timed under {@code FAILED}. */
  public void recordFailed(Duration duration) {
    ended(FAILED, duration);
  }

  /** A refresh skipped because another one runs. */
  public void recordSkipped() {
    runs(SKIPPED).increment();
  }

  private void ended(String outcome, Duration duration) {
    runs(outcome).increment();
    duration(outcome).record(duration);
  }

  private Counter runs(String outcome) {
    return meters.counter("refresh.runs", "outcome", outcome);
  }

  private Timer duration(String outcome) {
    return meters.timer("refresh.duration", "outcome", outcome);
  }
}
