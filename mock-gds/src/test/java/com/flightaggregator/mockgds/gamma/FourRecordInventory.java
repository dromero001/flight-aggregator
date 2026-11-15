package com.flightaggregator.mockgds.gamma;

import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.support.Fixtures;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.stream.IntStream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the inventory with four Gamma records, so that a limit of two divides the total. It
 * stays out of the test class on purpose: a nested configuration would apply to every test in it.
 */
@TestConfiguration
class FourRecordInventory {

  @Bean
  @Primary
  ProviderInventory fourRecordInventory() {
    var first = OffsetDateTime.parse("2026-11-20T08:15:00+01:00");
    var records =
        IntStream.range(0, 4)
            .<ProviderRecord>mapToObj(
                i ->
                    new ValidLeg(
                        new Leg(
                            "IB",
                            3170 + i,
                            "MAD",
                            "LHR",
                            first.plusDays(i),
                            first.plusDays(i).plusHours(2),
                            new BigDecimal("129.90"),
                            Provider.GAMMA)))
            .toList();
    return Fixtures.inventoryOf(records);
  }
}
