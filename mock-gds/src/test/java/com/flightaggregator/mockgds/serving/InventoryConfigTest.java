package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.GeneratorConfig;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.support.MockGdsTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * The served inventory comes from the generator's beans: a fixed clock, and a configuration whose
 * seed differs from the default one, so prices from any other source would differ.
 */
@MockGdsTest
class InventoryConfigTest {

  private static final Instant NOW = Instant.parse("2027-01-10T23:30:00Z");

  @TestBean(enforceOverride = true)
  private GeneratorConfig generatorConfig;

  @TestBean private Clock clock;

  @Autowired private Dataset dataset;
  @Autowired private ProviderInventory inventory;

  static GeneratorConfig generatorConfig() {
    var defaults = GeneratorConfig.defaults();
    return new GeneratorConfig(
        defaults.seed() + 1,
        defaults.windowDays(),
        defaults.maxDailyFlightsPerHubRoute(),
        defaults.maxDailyFlightsPerNonHubRoute(),
        defaults.operatingProbability(),
        defaults.twoProviderShare(),
        defaults.threeProviderShare(),
        defaults.malformedShare(),
        defaults.priceBucket());
  }

  static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void servesEachRecordAtThePriceItWasGeneratedWith() {
    for (var provider : Provider.values()) {
      var records = dataset.recordsFor(provider);

      assertThat(inventory.page(provider, RecordFilter.all(), 0, records.size()).records())
          .as(provider.name())
          .isNotEmpty()
          .allSatisfy(
              served -> assertThat(served.price()).isEqualTo(served.record().leg().priceEur()));
    }
  }

  @Test
  void startsTheWindowOnTheClocksUtcDate() {
    var firstDeparture =
        Arrays.stream(Provider.values())
            .flatMap(provider -> dataset.recordsFor(provider).stream())
            .map(record -> record.leg().departure().toLocalDate())
            .min(Comparator.naturalOrder())
            .orElseThrow();

    assertThat(firstDeparture).isEqualTo(LocalDate.ofInstant(NOW, ZoneOffset.UTC));
  }
}
