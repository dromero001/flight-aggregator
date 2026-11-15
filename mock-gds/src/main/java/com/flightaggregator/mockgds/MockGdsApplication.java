package com.flightaggregator.mockgds;

import com.flightaggregator.mockgds.dataset.DatasetGenerator;
import com.flightaggregator.mockgds.dataset.GeneratorConfig;
import com.flightaggregator.mockgds.pricing.PriceModel;
import com.flightaggregator.mockgds.reference.ReferenceData;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** The three mock providers behind one HTTP server. */
@SpringBootApplication
public class MockGdsApplication {

  public static void main(String[] args) {
    SpringApplication.run(MockGdsApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The dataset is generated once, at startup, with the default configuration and the standard
   * reference data: regenerating it mid-run would move records under a client that is paging.
   */
  @Bean
  ProviderInventory providerInventory(Clock clock) {
    var config = GeneratorConfig.defaults();
    var reference = ReferenceData.standard();
    var dataset = new DatasetGenerator(config, reference).generate(clock);
    var prices = new PriceModel(config.seed(), config.priceBucket(), reference);
    return new ProviderInventory(dataset, prices, clock);
  }
}
