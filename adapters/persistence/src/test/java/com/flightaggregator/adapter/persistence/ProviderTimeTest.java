package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderTimeTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-11-20T08:15:00+01:00",
        "2026-11-20T14:00:00-05:00",
        "2026-11-20T09:30:00+00:00",
        "2026-11-20T09:30:00.123456+05:30"
      })
  void roundTripsAnInstantAndItsOffset(String time) throws SQLException {
    OffsetDateTime written = OffsetDateTime.parse(time);

    try (Connection connection = PostgresTestDatabase.dataSource().getConnection();
        Statement ddl = connection.createStatement()) {
      ddl.execute("CREATE TEMPORARY TABLE provider_time (at timestamptz, at_offset int)");
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO provider_time VALUES (CAST(? AS timestamptz), CAST(? AS int))")) {
        insert.setString(1, ProviderTime.instantCell(written));
        insert.setString(2, ProviderTime.offsetCell(written));
        insert.executeUpdate();
      }
      try (ResultSet rs = ddl.executeQuery("SELECT at, at_offset FROM provider_time")) {
        assertThat(rs.next()).isTrue();
        assertThat(ProviderTime.read(rs, "at", "at_offset")).isEqualTo(written);
      }
    }
  }
}
