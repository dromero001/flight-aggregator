package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CopyRowsTest {

  private final DataSourceTransactionManager transactions =
      new DataSourceTransactionManager(PostgresTestDatabase.dataSource());
  private final TransactionTemplate transaction = new TransactionTemplate(transactions);
  private final JdbcClient jdbc = JdbcClient.create(PostgresTestDatabase.dataSource());

  /**
   * Copies the rows into a temporary table and reads them back, on one transaction's connection.
   */
  private List<String> roundTrip(String[]... rows) {
    return transaction.execute(
        status -> {
          jdbc.sql("CREATE TEMP TABLE copied (a text, b text) ON COMMIT DROP").update();
          try (CopyRows copy = CopyRows.open(connection(), "COPY copied (a, b) FROM STDIN")) {
            for (String[] row : rows) {
              copy.row(row);
            }
            copy.finish();
          }
          return jdbc.sql(
                  "SELECT coalesce(a, '<null>') || '|' || coalesce(b, '<null>') FROM copied")
              .query(String.class)
              .list();
        });
  }

  private java.sql.Connection connection() {
    return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
        PostgresTestDatabase.dataSource());
  }

  @Test
  void escapesTheTextFormatsSpecialCharacters() {
    String nasty = "a\tb\\c\nd\re";

    assertThat(roundTrip(new String[] {nasty, "x"})).containsExactly(nasty + "|x");
  }

  @Test
  void writesNullAsNull() {
    assertThat(roundTrip(new String[] {null, "\\N"})).containsExactly("<null>|\\N");
  }
}
