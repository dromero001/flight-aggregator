package com.flightaggregator.bootstrap.ingestion;

import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Provider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Run reports written by hand, for the tests of what the job makes of them. */
final class Reports {

  static final Instant STARTED_AT = Instant.parse("2026-11-20T08:00:00Z");
  static final Duration DURATION = Duration.ofSeconds(90);

  /** The newest quote of a fresh download: its last page arrived 30 seconds into the run. */
  static final Instant FRESH_QUOTE = STARTED_AT.plusSeconds(30);

  /** The newest quote of kept offers: a run a day before stored them. */
  static final Instant KEPT_QUOTE = STARTED_AT.minus(Duration.ofDays(1));

  private Reports() {}

  static IngestionReport published(Publication publication, ProviderReport... providers) {
    return new IngestionReport(
        STARTED_AT,
        DURATION,
        IngestionReport.Outcome.PUBLISHED,
        Optional.of(publication),
        List.of(providers));
  }

  static IngestionReport unpublished(IngestionReport.Outcome outcome, ProviderReport... providers) {
    return new IngestionReport(STARTED_AT, DURATION, outcome, Optional.empty(), List.of(providers));
  }

  /**
   * Four offers fetched, one discarded for its price, three used and one lost to another's; the
   * newest quoted at {@link #FRESH_QUOTE}.
   */
  static ProviderReport fresh(Provider provider) {
    return new ProviderReport(
        provider,
        Optional.empty(),
        Optional.empty(),
        4,
        Map.of(DiscardReason.PRICE, 1),
        3,
        1,
        Optional.of(FRESH_QUOTE));
  }

  /** Two stored offers kept and used, the newest quoted at {@link #KEPT_QUOTE}. */
  static ProviderReport kept(Provider provider, FailureReason reason, Throwable cause) {
    return new ProviderReport(
        provider,
        Optional.of(reason),
        Optional.of(cause),
        0,
        Map.of(),
        2,
        0,
        Optional.of(KEPT_QUOTE));
  }
}
