package com.flightaggregator.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The one encoding of a provider's local time in the database: the instant in a {@code timestamptz}
 * column and the provider's offset, in seconds, in an {@code int} column beside it. The offset is
 * kept because a {@code timestamptz} does not remember it (§4.2).
 */
final class ProviderTime {

  private ProviderTime() {}

  /** The instant, as the {@code COPY} cell of the {@code timestamptz} column. */
  static String instantCell(OffsetDateTime time) {
    return time.toInstant().toString();
  }

  /** The offset in seconds, as the {@code COPY} cell of the {@code int} column. */
  static String offsetCell(OffsetDateTime time) {
    return Integer.toString(time.getOffset().getTotalSeconds());
  }

  /** The stored instant at the offset the provider quoted it in. */
  static OffsetDateTime read(ResultSet rs, String instantColumn, String offsetColumn)
      throws SQLException {
    return rs.getObject(instantColumn, OffsetDateTime.class)
        .withOffsetSameInstant(ZoneOffset.ofTotalSeconds(rs.getInt(offsetColumn)));
  }
}
