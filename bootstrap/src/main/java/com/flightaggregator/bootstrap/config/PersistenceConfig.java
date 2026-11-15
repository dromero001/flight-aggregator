package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.persistence.JdbcAirportCatalogue;
import com.flightaggregator.adapter.persistence.JdbcIngestionStatusStore;
import com.flightaggregator.adapter.persistence.JdbcItineraryReader;
import com.flightaggregator.adapter.persistence.JdbcItineraryRepository;
import com.flightaggregator.adapter.persistence.JdbcOfferStore;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PostgreSQL stores. Each store that writes, and the itinerary reader, is built from Boot's one
 * transaction manager (a {@code JdbcTransactionManager} over Boot's one {@code DataSource}) and
 * takes its connections and its transactions from it, so its statements and {@code COPY} always run
 * in that manager's transaction: no store can be wired to a second data source. {@code
 * FlightAggregatorApplicationTest.writesEveryStoreInTheTransactionOfBootsDataSource} proves it in
 * the application for the stores that write; the reader's constructor takes only the manager.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

  /** Day partitions ahead of today: the 30-day horizon plus 7 days, should a run fail (§4.3). */
  private static final int PARTITION_DAYS_AHEAD = 37;

  @Bean
  OfferStore offerStore(DataSourceTransactionManager transactions) {
    return new JdbcOfferStore(transactions);
  }

  @Bean
  AirportCatalogue airportCatalogue(JdbcClient jdbc) {
    return new JdbcAirportCatalogue(jdbc);
  }

  @Bean
  ItineraryRepository itineraryRepository(
      DataSourceTransactionManager transactions, JsonMapper json) {
    return new JdbcItineraryRepository(
        transactions, json.tokenStreamFactory(), PARTITION_DAYS_AHEAD);
  }

  @Bean
  ItineraryReader itineraryReader(DataSourceTransactionManager transactions, JsonMapper json) {
    return new JdbcItineraryReader(transactions, json.tokenStreamFactory());
  }

  @Bean
  IngestionStatusStore ingestionStatusStore(DataSourceTransactionManager transactions) {
    return new JdbcIngestionStatusStore(transactions);
  }
}
