package com.flightaggregator.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.application.port.out.Publication;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IngestionReportTest {

  @Test
  void copiesTheProviderReports() {
    List<ProviderReport> providers = new ArrayList<>();
    IngestionReport report =
        new IngestionReport(
            Instant.parse("2026-11-20T08:00:00Z"),
            Duration.ZERO,
            IngestionReport.Outcome.NOTHING_TO_PUBLISH,
            Optional.empty(),
            providers,
            Optional.empty());

    providers.add(null);

    assertThat(report.providers()).isEmpty();
  }

  /** A run published a generation exactly when its outcome says so. */
  @Test
  void refusesAPublicationThatDisagreesWithTheOutcome() {
    Publication publication = new Publication(1, 10, 0, Instant.parse("2026-12-28T00:00:00Z"));

    assertThatThrownBy(() -> report(IngestionReport.Outcome.PUBLISHED, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A PUBLISHED run must have a publication");
    assertThatThrownBy(
            () -> report(IngestionReport.Outcome.CEILING_EXCEEDED, Optional.of(publication)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A CEILING_EXCEEDED run cannot have a publication");
  }

  private static IngestionReport report(
      IngestionReport.Outcome outcome, Optional<Publication> publication) {
    return new IngestionReport(
        Instant.parse("2026-11-20T08:00:00Z"),
        Duration.ZERO,
        outcome,
        publication,
        List.of(),
        Optional.empty());
  }
}
