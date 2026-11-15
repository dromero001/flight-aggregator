package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.service.IngestInventoryService;
import com.flightaggregator.bootstrap.observability.LoggedDiscardedRecords;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The ingestion service, over the three providers and the stores. */
@Configuration(proxyBeanMethods = false)
class IngestionConfig {

  @Bean
  DiscardedRecords discardedRecords(MeterRegistry meters) {
    return new LoggedDiscardedRecords(meters);
  }

  @Bean
  IngestInventory ingestInventory(
      List<WiredProvider> providers,
      OfferStore offers,
      AirportCatalogue airports,
      DiscardedRecords discarded,
      ItineraryRepository itineraries,
      Clock clock,
      @Value("${ingestion.max-itineraries}") long maxItineraries) {
    return new IngestInventoryService(
        providers.stream().map(WiredProvider::adapter).toList(),
        offers,
        airports,
        discarded,
        itineraries,
        clock,
        maxItineraries);
  }
}
