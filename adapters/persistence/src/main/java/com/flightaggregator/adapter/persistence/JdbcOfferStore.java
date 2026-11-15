package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps each provider's last complete download in {@code provider_offer}. A replacement is one
 * transaction, a delete and a bulk {@code COPY}, each bounded by {@link StatementTimeout}, so a
 * failure leaves the previous offers.
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

  /**
   * Its connections and its transactions come from the one manager, so its statements and its
   * {@code COPY} always run in that manager's transaction: it cannot be given a second data source.
   */
  public JdbcOfferStore(DataSourceTransactionManager transactions) {
    this.dataSource = requireNonNull(transactions.getDataSource(), "the manager's DataSource");
    this.transaction = new TransactionTemplate(transactions);
    this.jdbc = JdbcClient.create(dataSource);
  }

  @Override
  public void replace(Provider provider, List<Leg> offers) {
    transaction.executeWithoutResult(
        status -> {
          StatementTimeout.boundTheTransaction(jdbc);
          jdbc.sql("DELETE FROM provider_offer WHERE provider = ?").param(provider.name()).update();
          copy(provider, offers);
        });
  }

  private void copy(Provider provider, List<Leg> offers) {
    try (CopyRows rows = CopyRows.open(dataSource, COPY)) {
      int position = 0;
      for (Leg leg : offers) {
        rows.row(
            provider.name(),
            Integer.toString(position++),
            leg.carrier().code(),
            Integer.toString(leg.flightNumber()),
            leg.origin().code(),
            leg.destination().code(),
            ProviderTime.instantCell(leg.departure()),
            ProviderTime.offsetCell(leg.departure()),
            ProviderTime.instantCell(leg.arrival()),
            ProviderTime.offsetCell(leg.arrival()),
            leg.price().amount().toPlainString(),
            leg.quotedAt().toString());
      }
      rows.finish();
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
        ProviderTime.read(rs, "departure", "departure_offset"),
        ProviderTime.read(rs, "arrival", "arrival_offset"),
        new Money(rs.getBigDecimal("price")),
        provider,
        rs.getObject("quoted_at", OffsetDateTime.class).toInstant());
  }
}
