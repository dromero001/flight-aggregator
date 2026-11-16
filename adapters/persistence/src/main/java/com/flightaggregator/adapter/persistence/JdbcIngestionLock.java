package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.IngestionLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The ingestion's lock as ShedLock keeps it, a row of {@code shedlock} (V4) that the job takes over
 * the database's clock: held while its {@code lock_until} is ahead of that clock. ShedLock moves it
 * 29 minutes ahead when a run takes the lock and back to the release when the run ends, so a run
 * whose instance died holds it until then, as it does for "Run ingestion now". Read in a {@link
 * BoundedSnapshot}, since the status screen and the health probe wait for it.
 */
public final class JdbcIngestionLock implements IngestionLock {

  /** ShedLock's own clock for PostgreSQL, which writes the lock's times in UTC. */
  private static final String HELD =
      "SELECT EXISTS (SELECT 1 FROM shedlock WHERE name = ?"
          + " AND lock_until > timezone('utc', CURRENT_TIMESTAMP))";

  private final BoundedSnapshot snapshot;
  private final JdbcClient jdbc;
  private final String name;

  /** {@code name} is the lock's ShedLock name, the job's. */
  public JdbcIngestionLock(DataSourceTransactionManager transactions, String name) {
    this.snapshot = new BoundedSnapshot(transactions);
    this.jdbc =
        JdbcClient.create(requireNonNull(transactions.getDataSource(), "the manager's DataSource"));
    this.name = name;
  }

  @Override
  public boolean isHeld() {
    return snapshot.read(() -> jdbc.sql(HELD).param(name).query(Boolean.class).single());
  }
}
