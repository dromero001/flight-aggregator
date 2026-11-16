package com.flightaggregator.adapter.persistence.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What the tests of concurrent writes wait for, read from PostgreSQL rather than timed. */
public final class LockWaits {

  private LockWaits() {}

  /**
   * Waits until a statement of another backend waits for a lock the backend {@code holder} holds.
   */
  public static void awaitAStatementBlockedBy(JdbcClient jdbc, int holder)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")
            .param(holder)
            .query(Long.class)
            .single()
        == 0) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("nothing waited for backend " + holder);
      }
      Thread.sleep(20);
    }
  }

  /** The backend that serves the connection, as {@code pg_blocking_pids} names it. */
  public static int backendPid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
      pid.next();
      return pid.getInt(1);
    }
  }
}
