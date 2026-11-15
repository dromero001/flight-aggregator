package com.flightaggregator.application.port.in;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * What one run did with one provider. {@code offersUsed} counts the stored offers, fresh or kept,
 * that took part in the run. {@code cause} is set for every failure, so that a caller can log it:
 * the application itself never logs. {@code newestQuotedAt} is the newest quote among the
 * provider's stored offers after the run, fresh or kept, so that how old its data is can be known
 * even after a restart; it is empty when nothing is stored.
 */
public record ProviderReport(
    Provider provider,
    Status status,
    Optional<FailureReason> failure,
    Optional<Throwable> cause,
    int offersFetched,
    Map<DiscardReason, Integer> discarded,
    int offersUsed,
    int lostToDeduplication,
    Optional<Instant> newestQuotedAt) {

  public ProviderReport {
    discarded = Map.copyOf(discarded);
  }

  /** Whether the run stored a new download, or kept the last good one. */
  public enum Status {
    FRESH,
    KEPT
  }
}
