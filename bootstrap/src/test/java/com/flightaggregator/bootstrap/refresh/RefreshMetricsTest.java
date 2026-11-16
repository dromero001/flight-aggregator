package com.flightaggregator.bootstrap.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshReport.ProviderRefresh;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
   * Every outcome is registered at zero when the metrics are built, so Prometheus's {@code rate}
   * and {@code increase} see the first refresh of each, which they would miss on a counter created
   * by it (E-T6-1, built on 2026-11-16).
   */
  @Test
  void registersEveryOutcomeAtZeroBeforeTheFirstRefresh() {
    List<String> ended =
        Stream.concat(
                Arrays.stream(RefreshReport.Outcome.values()).map(Enum::name), Stream.of("FAILED"))
            .toList();

    assertThat(meters.find("refresh.runs").counters())
        .extracting(counter -> counter.getId().getTag("outcome"), Counter::count)
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(ended.stream(), Stream.of("SKIPPED"))
                .map(outcome -> tuple(outcome, 0.0))
                .toList());
    assertThat(meters.find("refresh.duration").timers())
        .extracting(timer -> timer.getId().getTag("outcome"), Timer::count)
        .containsExactlyInAnyOrderElementsOf(
            ended.stream().map(outcome -> tuple(outcome, 0L)).toList());
  }

  private double runs(String outcome) {
    return meters.get("refresh.runs").tag("outcome", outcome).counter().count();
  }
}
