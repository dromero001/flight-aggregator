package com.flightaggregator.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Writes rows to a {@code COPY ... FROM STDIN} in PostgreSQL's text format, on the connection it is
 * given. Closing before {@link #finish()} cancels the copy, so nothing is loaded.
 */
final class CopyRows implements AutoCloseable {

  private final CopyIn copy;
  private final String copySql;
  private boolean finished;

  private CopyRows(CopyIn copy, String copySql) {
    this.copy = copy;
    this.copySql = copySql;
  }

  static CopyRows open(Connection connection, String copySql) {
    try {
      return new CopyRows(
          connection.unwrap(PGConnection.class).getCopyAPI().copyIn(copySql), copySql);
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
    if (finished) {
      return;
    }
    try {
      copy.cancelCopy();
    } catch (SQLException e) {
      throw new UncategorizedSQLException("Cancel COPY", copySql, e);
    }
  }

  private static String escaped(String cell) {
    return cell.replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
