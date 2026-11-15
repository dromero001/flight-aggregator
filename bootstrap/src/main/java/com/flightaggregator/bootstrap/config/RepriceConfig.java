package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.RepriceItinerary;
import com.flightaggregator.application.port.in.RepriceSettings;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.service.RepriceItineraryService;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The re-price use case (§6.3, §6.5), over the three providers and the stores. */
@Configuration(proxyBeanMethods = false)
class RepriceConfig {

  /**
   * Q9: at most 3 alternatives, and no quote started after 10 s, since one quote with its retries
   * can take about 8 s.
   */
  private static final RepriceSettings SETTINGS = new RepriceSettings(3, Duration.ofSeconds(10));

  @Bean
  RepriceItinerary repriceItinerary(
      ItineraryReader reader,
      OfferStore offers,
      List<FlightProvider> providers,
      ItineraryRepository itineraries,
      Clock clock) {
    return new RepriceItineraryService(reader, offers, providers, itineraries, clock, SETTINGS);
  }
}
