package com.flightaggregator.mockgds.beta;

import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.support.Fixtures;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the inventory with one that holds no record. It stays out of the test class on purpose:
 * a nested configuration would apply to every test in it.
 */
@TestConfiguration
class EmptyInventory {

  @Bean
  @Primary
  ProviderInventory emptyInventory() {
    return Fixtures.inventoryOf(List.of());
  }
}
