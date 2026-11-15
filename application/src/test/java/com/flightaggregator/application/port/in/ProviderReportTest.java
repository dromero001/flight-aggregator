package com.flightaggregator.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.out.DiscardReason;
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
            ProviderReport.Status.FRESH,
            Optional.empty(),
            Optional.empty(),
            1,
            discarded,
            0,
            0);

    discarded.put(DiscardReason.PRICE, 5);

    assertThat(report.discarded()).isEqualTo(Map.of(DiscardReason.SHAPE, 1));
  }
}
