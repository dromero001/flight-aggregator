package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.SearchItineraries;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.service.SearchItinerariesService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The search use case (§5.4, §6.2, §6.4), over the itinerary reader, Valkey's result cache and
 * refresh coordination, and the background refresh, with the freshness threshold of {@code
 * search.freshness-threshold}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {

  @Bean
  SearchItineraries searchItineraries(
      ItineraryReader reader,
      SearchCache cache,
      RefreshCoordination coordination,
      RefreshScheduler refreshes,
      Clock clock,
      SearchProperties search) {
    return new SearchItinerariesService(
        reader, cache, coordination, refreshes, clock, search.freshnessThreshold());
  }
}
