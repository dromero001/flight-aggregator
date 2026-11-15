package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.SearchItineraries;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.service.SearchItinerariesService;
import com.flightaggregator.domain.Airport;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.function.SingletonSupplier;

/**
 * The search use case (§5.4, §6.2, §6.4), over the itinerary reader, Valkey's result cache, the
 * airport catalogue, refresh coordination and the background refresh, with the freshness threshold
 * of {@code search.freshness-threshold}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {

  /**
   * The catalogue is migration V2's fixed set of airports, while its store reads the table on every
   * call, so the use case gets it read once, at the first search that needs it; a read that fails
   * is not kept, and the next search reads again.
   */
  @Bean
  SearchItineraries searchItineraries(
      ItineraryReader reader,
      SearchCache cache,
      AirportCatalogue airports,
      RefreshCoordination coordination,
      RefreshScheduler refreshes,
      Clock clock,
      SearchProperties search) {
    SingletonSupplier<Set<Airport>> readOnce = SingletonSupplier.of(airports::airports);
    return new SearchItinerariesService(
        reader,
        cache,
        readOnce::obtain,
        coordination,
        refreshes,
        clock,
        search.freshnessThreshold());
  }
}
