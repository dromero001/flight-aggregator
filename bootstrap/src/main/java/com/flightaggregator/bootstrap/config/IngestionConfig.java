package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.service.IngestInventoryService;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.flightaggregator.bootstrap.ingestion.IngestionMetrics;
import com.flightaggregator.bootstrap.observability.LoggedDiscardedRecords;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The ingestion service, over the three providers and the stores, and the job that runs it. */
@Configuration(proxyBeanMethods = false)
class IngestionConfig {

  @Bean
  DiscardedRecords discardedRecords(MeterRegistry meters) {
    return new LoggedDiscardedRecords(meters);
  }

  @Bean
  IngestInventory ingestInventory(
      List<FlightProvider> providers,
      OfferStore offers,
      AirportCatalogue airports,
      DiscardedRecords discarded,
      ItineraryRepository itineraries,
      IngestionStatusStore statuses,
      Clock clock,
      @Value("${ingestion.max-itineraries}") long maxItineraries) {
    return new IngestInventoryService(
        providers, offers, airports, discarded, itineraries, statuses, clock, maxItineraries);
  }

  @Bean
  IngestionMetrics ingestionMetrics(MeterRegistry meters, Clock clock) {
    return new IngestionMetrics(meters, clock);
  }

  @Bean
  IngestionJob ingestionJob(
      IngestInventory ingestion, IngestionMetrics metrics, LockingTaskExecutor locks, Clock clock) {
    return new IngestionJob(ingestion, metrics, locks, clock);
  }
}
