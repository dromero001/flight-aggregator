package com.flightaggregator.mockgds.reference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LowCostCarrierTest {

  @Test
  void needsAtLeastTwoBasesToFlyPointToPoint() {
    assertThatThrownBy(() -> new LowCostCarrier("FR", "Ryanair", List.of("EIN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least two bases");
  }
}
