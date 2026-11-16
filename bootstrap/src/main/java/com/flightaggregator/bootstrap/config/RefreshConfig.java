package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.RefreshStaleRoute;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.application.service.RefreshStaleRouteService;
import com.flightaggregator.bootstrap.refresh.BackgroundRefresh;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The stale-route refresh (§4.1, §6.4), over the three providers, the stores and Valkey's refresh
 * coordination, and the scheduler that search asks to start it in the background.
 */
@Configuration(proxyBeanMethods = false)
class RefreshConfig {

  @Bean
  RefreshStaleRoute refreshStaleRoute(
      List<FlightProvider> providers,
      OfferStore offers,
      AirportCatalogue airports,
      DiscardedRecords discarded,
      ItineraryRepository itineraries,
      RefreshCoordination coordination,
      Clock clock,
      ThreadFactory contextThreads) {
    return new RefreshStaleRouteService(
        providers, offers, airports, discarded, itineraries, coordination, clock, contextThreads);
  }

  @Bean
  RefreshScheduler refreshScheduler(
      RefreshStaleRoute refreshes,
      LockingTaskExecutor locks,
      RefreshCoordination coordination,
      Clock clock) {
    return new BackgroundRefresh(refreshes, locks, coordination, clock);
  }
}
