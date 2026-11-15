package com.flightaggregator.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.domain.Provider;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderReportTest {

  @Test
  void copiesTheDiscardedCounts() {
    Map<DiscardReason, Integer> discarded = new HashMap<>(Map.of(DiscardReason.SHAPE, 1));
    ProviderReport report =
        new ProviderReport(
            Provider.ALPHA,
            Optional.empty(),
            Optional.empty(),
            1,
            discarded,
            0,
            0,
            Optional.empty());

    discarded.put(DiscardReason.PRICE, 5);

    assertThat(report.discarded()).isEqualTo(Map.of(DiscardReason.SHAPE, 1));
  }

  /** A provider is kept exactly when its download failed, so the two can never disagree. */
  @Test
  void isKeptExactlyWhenItsDownloadFailed() {
    assertThat(report(Optional.empty()).status()).isEqualTo(ProviderReport.Status.FRESH);
    assertThat(report(Optional.of(FailureReason.TIMEOUT)).status())
        .isEqualTo(ProviderReport.Status.KEPT);
  }

  @Test
  void refusesACauseWithoutAFailure() {
    assertThatThrownBy(
            () ->
                new ProviderReport(
                    Provider.ALPHA,
                    Optional.empty(),
                    Optional.of(new IllegalStateException("boom")),
                    0,
                    Map.of(),
                    0,
                    0,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A FRESH provider has no failure cause");
  }

  private static ProviderReport report(Optional<FailureReason> failure) {
    return new ProviderReport(
        Provider.ALPHA, failure, Optional.empty(), 0, Map.of(), 0, 0, Optional.empty());
  }
}
