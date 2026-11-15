package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.domain.Airport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAirportCatalogueTest {

  private final JdbcAirportCatalogue catalogue =
      new JdbcAirportCatalogue(JdbcClient.create(PostgresTestDatabase.dataSource()));

  @Test
  void loadsTheSeededCatalogue() {
    assertThat(catalogue.airports()).hasSize(32).contains(new Airport("MAD"));
    assertThat(catalogue.airports()).doesNotContain(new Airport("ZZZ"));
  }
}
