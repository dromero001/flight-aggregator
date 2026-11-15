package com.flightaggregator.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Writes rows to a {@code COPY ... FROM STDIN} in PostgreSQL's text format, inside the caller's
 * transaction, so the copy commits or rolls back with the rest of it. Closing before {@link
 * #finish()} cancels the copy, so nothing is loaded; closing always releases the connection.
 */
final class CopyRows implements AutoCloseable {

  private final DataSource dataSource;
  private final Connection connection;
  private final CopyIn copy;
  private final String copySql;
  private boolean finished;

  private CopyRows(DataSource dataSource, Connection connection, CopyIn copy, String copySql) {
    this.dataSource = dataSource;
    this.connection = connection;
    this.copy = copy;
    this.copySql = copySql;
  }

  /**
   * Starts the copy on the connection of the transaction in progress on the data source. Without
   * one it throws {@link IllegalStateException}, rather than copy on a connection that commits on
   * its own.
   */
  static CopyRows open(DataSource dataSource, String copySql) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
        throw new IllegalStateException(
            "COPY must run in a transaction on its DataSource, or it commits on its own: "
                + copySql);
      }
      return new CopyRows(dataSource, connection, copyIn(connection, copySql), copySql);
    } catch (RuntimeException e) {
      DataSourceUtils.releaseConnection(connection, dataSource);
      throw e;
    }
  }

  private static CopyIn copyIn(Connection connection, String copySql) {
    try {
      return connection.unwrap(PGConnection.class).getCopyAPI().copyIn(copySql);
    } catch (SQLException e) {
      throw new UncategorizedSQLException("Open COPY", copySql, e);
    }
  }

  /** Writes one row; a null cell is written as {@code \N}. */
  void row(String... cells) {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) {
        line.append('\t');
      }
      line.append(cells[i] == null ? "\\N" : escaped(cells[i]));
    }
    line.append('\n');
    byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
    try {
      copy.writeToCopy(bytes, 0, bytes.length);
    } catch (SQLException e) {
      throw new UncategorizedSQLException("Write COPY row", copySql, e);
    }
  }

  /** Ends the copy and returns the number of rows loaded. */
  long finish() {
    try {
      long rows = copy.endCopy();
      finished = true;
      return rows;
    } catch (SQLException e) {
      throw new UncategorizedSQLException("End COPY", copySql, e);
    }
  }

  @Override
  public void close() {
    try {
      if (!finished) {
        copy.cancelCopy();
      }
    } catch (SQLException e) {
      throw new UncategorizedSQLException("Cancel COPY", copySql, e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private static String escaped(String cell) {
    return cell.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
