package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps each provider's last complete download in {@code provider_offer}. A replacement is one
 * transaction, a delete and a bulk {@code COPY}, so a failure leaves the previous offers.
 */
public final class JdbcOfferStore implements OfferStore {

  private static final String COPY =
      "COPY provider_offer (provider, position, carrier, flight_number, origin, destination,"
          + " departure, departure_offset, arrival, arrival_offset, price, quoted_at)"
          + " FROM STDIN";

  private static final String SELECT =
      "SELECT carrier, flight_number, origin, destination, departure, departure_offset, arrival,"
          + " arrival_offset, price, quoted_at FROM provider_offer WHERE provider = ?"
          + " ORDER BY position";

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final JdbcClient jdbc;

  public JdbcOfferStore(DataSource dataSource, TransactionTemplate transaction) {
    this.dataSource = dataSource;
    this.transaction = transaction;
    this.jdbc = JdbcClient.create(dataSource);
  }

  @Override
  public void replace(Provider provider, List<Leg> offers) {
    transaction.executeWithoutResult(
        status -> {
          jdbc.sql("DELETE FROM provider_offer WHERE provider = ?").param(provider.name()).update();
          copy(provider, offers);
        });
  }

  private void copy(Provider provider, List<Leg> offers) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (CopyRows rows = CopyRows.open(connection, COPY)) {
      int position = 0;
      for (Leg leg : offers) {
        rows.row(
            provider.name(),
            Integer.toString(position++),
            leg.carrier().code(),
            Integer.toString(leg.flightNumber()),
            leg.origin().code(),
            leg.destination().code(),
            leg.departure().toInstant().toString(),
            Integer.toString(leg.departure().getOffset().getTotalSeconds()),
            leg.arrival().toInstant().toString(),
            Integer.toString(leg.arrival().getOffset().getTotalSeconds()),
            leg.price().amount().toPlainString(),
            leg.quotedAt().toString());
      }
      rows.finish();
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @Override
  public List<Leg> offers(Provider provider) {
    return jdbc.sql(SELECT).param(provider.name()).query((rs, rowNum) -> leg(provider, rs)).list();
  }

  private static Leg leg(Provider provider, ResultSet rs) throws SQLException {
    return new Leg(
        new Carrier(rs.getString("carrier")),
        rs.getInt("flight_number"),
        new Airport(rs.getString("origin")),
        new Airport(rs.getString("destination")),
        local(rs, "departure", "departure_offset"),
        local(rs, "arrival", "arrival_offset"),
        new Money(rs.getBigDecimal("price")),
        provider,
        rs.getObject("quoted_at", OffsetDateTime.class).toInstant());
  }

  /** The stored instant at the offset the provider quoted it in. */
  private static OffsetDateTime local(ResultSet rs, String instant, String offset)
      throws SQLException {
    return rs.getObject(instant, OffsetDateTime.class)
        .withOffsetSameInstant(ZoneOffset.ofTotalSeconds(rs.getInt(offset)));
  }
}
