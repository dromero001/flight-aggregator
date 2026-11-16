package com.flightaggregator.bootstrap.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshReport.ProviderRefresh;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RefreshMetricsTest {

  private static final Airport MADRID = new Airport("MAD");

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final RefreshMetrics metrics = new RefreshMetrics(meters);

  /** A refresh is counted and timed under its outcome, as a run is (plan 5 Task 7's extra). */
  @Test
  void countsAndTimesARefreshUnderItsOutcome() {
    metrics.record(
        new RefreshReport(
            MADRID,
            RefreshReport.Outcome.REFRESHED,
            8,
            3,
            Map.of(Provider.ALPHA, ProviderRefresh.FRESH)),
        Duration.ofSeconds(37));

    assertThat(runs("REFRESHED")).isEqualTo(1);
    assertThat(
            meters
                .get("refresh.duration")
                .tag("outcome", "REFRESHED")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(37);
  }

  /** A refresh that ended with an exception instead of a report is {@code FAILED}, as a run is. */
  @Test
  void countsAndTimesAFailedRefreshAsFailed() {
    metrics.recordFailed(Duration.ofSeconds(5));

    assertThat(runs("FAILED")).isEqualTo(1);
    assertThat(
            meters
                .get("refresh.duration")
                .tag("outcome", "FAILED")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(5);
  }

  /**
   * A refresh skipped because another one runs is counted under {@code SKIPPED} in the same meter
   * (plan 5's closing round 1), so the pressure on the one refresh at a time reads beside what the
   * refreshes did; it is not timed, since nothing ran.
   */
  @Test
  void countsASkippedRefreshWithoutTimingIt() {
    metrics.recordSkipped();

    assertThat(runs("SKIPPED")).isEqualTo(1);
    assertThat(meters.find("refresh.duration").tag("outcome", "SKIPPED").timer()).isNull();
  }

  /**
   * Each outcome's meters are created by its first refresh, as every other counter of the
   * application is: none is registered at zero (E-T6-1, withdrawn by the user on 2026-11-16).
   */
  @Test
  void createsAnOutcomesMetersOnItsFirstRefresh() {
    assertThat(meters.find("refresh.runs").counters()).isEmpty();
    assertThat(meters.find("refresh.duration").timers()).isEmpty();

    metrics.recordSkipped();

    assertThat(meters.find("refresh.runs").counters())
        .extracting(counter -> counter.getId().getTag("outcome"))
        .containsExactly("SKIPPED");
    assertThat(meters.find("refresh.duration").timers()).isEmpty();
  }

  private double runs(String outcome) {
    return meters.get("refresh.runs").tag("outcome", outcome).counter().count();
  }
}
