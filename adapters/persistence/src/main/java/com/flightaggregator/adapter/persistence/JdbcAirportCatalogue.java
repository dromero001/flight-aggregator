package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.domain.Airport;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The airports of the {@code airport} table. */
public final class JdbcAirportCatalogue implements AirportCatalogue {

  private final JdbcClient jdbc;

  public JdbcAirportCatalogue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Set<Airport> airports() {
    return Set.copyOf(
        jdbc.sql("SELECT code FROM airport")
            .query((rs, rowNum) -> new Airport(rs.getString("code")))
            .list());
  }
}
