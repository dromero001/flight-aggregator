package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.persistence.JdbcAirportCatalogue;
import com.flightaggregator.adapter.persistence.JdbcItineraryRepository;
import com.flightaggregator.adapter.persistence.JdbcOfferStore;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PostgreSQL stores. Every store and the {@code TransactionTemplate} use Boot's one {@code
 * DataSource}: the template is Boot's, over that data source's transaction manager, so each store's
 * statements and {@code COPY} run in the template's transaction. This wiring and {@code
 * FlightAggregatorApplicationTest.writesEveryStoreInTheTransactionOfBootsDataSource} hold the rule.
 * {@code CopyRows} refuses a copy with no transaction at all, but not a copy on a second data
 * source while the first has one: Spring then gives the second a connection of its own, which
 * commits each statement.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

  /** Day partitions ahead of today: the 30-day horizon plus 7 days, should a run fail (§4.3). */
  private static final int PARTITION_DAYS_AHEAD = 37;

  @Bean
  OfferStore offerStore(DataSource dataSource, TransactionTemplate transaction) {
    return new JdbcOfferStore(dataSource, transaction);
  }

  @Bean
  AirportCatalogue airportCatalogue(JdbcClient jdbc) {
    return new JdbcAirportCatalogue(jdbc);
  }

  @Bean
  ItineraryRepository itineraryRepository(
      DataSource dataSource, TransactionTemplate transaction, JsonMapper json) {
    return new JdbcItineraryRepository(
        dataSource, transaction, json.tokenStreamFactory(), PARTITION_DAYS_AHEAD);
  }
}
