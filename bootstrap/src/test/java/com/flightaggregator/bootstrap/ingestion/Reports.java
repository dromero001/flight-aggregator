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

  /** Four offers fetched, one discarded for its price, three used and one lost to another's. */
  static ProviderReport fresh(Provider provider) {
    return new ProviderReport(
        provider,
        ProviderReport.Status.FRESH,
        Optional.empty(),
        Optional.empty(),
        4,
        Map.of(DiscardReason.PRICE, 1),
        3,
        1);
  }

  /** Two stored offers kept and used. */
  static ProviderReport kept(Provider provider, FailureReason reason, Throwable cause) {
    return new ProviderReport(
        provider,
        ProviderReport.Status.KEPT,
        Optional.of(reason),
        Optional.of(cause),
        0,
        Map.of(),
        2,
        0);
  }
}
