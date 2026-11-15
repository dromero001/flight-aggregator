package com.flightaggregator.application.port.out;

import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * What one run did with one provider, as the status store keeps it: a {@link ProviderReport}
 * without what only the run's log needs. Its {@link #status()} follows from {@code failure}, as the
 * report's does, so the two cannot disagree. {@code newestQuotedAt} is the newest quote among the
 * provider's stored offers after the run, fresh or kept.
 */
public record ProviderRun(
    Provider provider,
    Optional<FailureReason> failure,
    int offersUsed,
    Map<DiscardReason, Integer> discarded,
    Optional<Instant> newestQuotedAt) {

  public ProviderRun {
    discarded = Map.copyOf(discarded);
  }

  public static ProviderRun of(ProviderReport report) {
    return new ProviderRun(
        report.provider(),
        report.failure(),
        report.offersUsed(),
        report.discarded(),
        report.newestQuotedAt());
  }

  /** {@code KEPT} exactly when the run's download failed. */
  public ProviderReport.Status status() {
    return failure.isPresent() ? ProviderReport.Status.KEPT : ProviderReport.Status.FRESH;
  }
}
