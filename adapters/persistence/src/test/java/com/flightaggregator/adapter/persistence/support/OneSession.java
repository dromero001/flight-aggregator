package com.flightaggregator.adapter.persistence.support;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * One session of the test database for a whole test: every connection it hands out is the same one,
 * so a transaction and the statements after its commit run in one session, and a test can see
 * whether a setting outlived its transaction. {@link PostgresTestDatabase}'s data source opens a
 * new session for every connection, where each setting starts again at its default.
 */
public final class OneSession implements AutoCloseable {

  private final SingleConnectionDataSource dataSource;

  public OneSession() {
    try {
      this.dataSource =
          new SingleConnectionDataSource(PostgresTestDatabase.dataSource().getConnection(), true);
    } catch (SQLException e) {
      throw new IllegalStateException("cannot open a session of the test database", e);
    }
  }

  public DataSource dataSource() {
    return dataSource;
  }

  /** The setting's value in this session, inside the transaction in progress if there is one. */
  public String show(String setting) {
    return JdbcClient.create(dataSource).sql("SHOW " + setting).query(String.class).single();
  }

  @Override
  public void close() {
    dataSource.destroy();
  }
}
