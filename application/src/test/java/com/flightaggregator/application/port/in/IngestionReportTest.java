package com.flightaggregator.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            0,
            0,
            providers);

    providers.add(null);

    assertThat(report.providers()).isEmpty();
  }
}
