package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.DatasetGenerator;
import com.flightaggregator.mockgds.dataset.GeneratorConfig;
import com.flightaggregator.mockgds.pricing.PriceModel;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One generator behind the served inventory: its price model also prices every request. */
@Configuration
class InventoryConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  GeneratorConfig generatorConfig() {
    return GeneratorConfig.defaults();
  }

  @Bean
  ReferenceData referenceData() {
    return ReferenceData.standard();
  }

  @Bean
  DatasetGenerator datasetGenerator(GeneratorConfig config, ReferenceData reference) {
    return new DatasetGenerator(config, reference);
  }

  /** The model that priced the dataset, so a request is priced with the same seed and buckets. */
  @Bean
  PriceModel priceModel(DatasetGenerator generator) {
    return generator.prices();
  }

  /**
   * The dataset is generated once, at startup: regenerating it mid-run would move records under a
   * client that is paging.
   */
  @Bean
  Dataset dataset(DatasetGenerator generator, Clock clock) {
    return generator.generate(clock);
  }

  @Bean
  ProviderInventory providerInventory(Dataset dataset, PriceModel prices, Clock clock) {
    return new ProviderInventory(dataset, prices, clock);
  }
}
