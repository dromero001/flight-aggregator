package com.flightaggregator.adapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The bound on each statement of a transaction, so that a statement that hangs fails its
 * transaction, which rolls back, instead of holding its connection and its locks for ever.
 */
enum StatementTimeout {

  /**
   * A store's write: 5 minutes, far above the slowest statement (the {@code COPY} of every
   * itinerary into a slot with its six sort indexes, about 15 s measured by plan 5 Task 13, then
   * about 5 s of {@code ANALYZE}).
   */
  STORE("5min"),

  /**
   * A search read: 2 s. A read holds {@code ACCESS SHARE} on its slot, which a run's {@code
   * TRUNCATE} of that slot waits for.
   */
  SEARCH_READ("2s");

  private final String bound;

  StatementTimeout(String bound) {
    this.bound = bound;
  }

  /** Sets the bound for the rest of the transaction in progress, and for it alone. */
  void boundTheTransaction(JdbcClient jdbc) {
    jdbc.sql("SET LOCAL statement_timeout = '" + bound + "'").update();
  }
}
