package com.flightaggregator.adapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The bound on each statement of a store's transaction: 5 minutes, far above the slowest one (the
 * {@code COPY} of every itinerary, about 12 s measured), so that a statement that hangs fails its
 * transaction, which rolls back, instead of holding its locks for ever.
 */
final class StatementTimeout {

  private StatementTimeout() {}

  /** Sets the bound for the rest of the transaction in progress, and for it alone. */
  static void boundTheTransaction(JdbcClient jdbc) {
    jdbc.sql("SET LOCAL statement_timeout = '5min'").update();
  }
}
