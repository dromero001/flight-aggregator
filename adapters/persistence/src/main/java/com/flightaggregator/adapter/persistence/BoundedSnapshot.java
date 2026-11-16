package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.in.InventoryUnavailableException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way the stores read for a caller who is waiting (a search, a re-price, the admin status,
 * the health probe): one read-only transaction at {@code REPEATABLE READ}, whose statements all see
 * the snapshot taken at its first one, each bounded by {@link StatementTimeout#SEARCH_READ}. A read
 * that takes longer is cancelled and rolls back, so it never holds its connection, or its locks,
 * past the bound, and fails with the port's {@link InventoryUnavailableException}, logged at WARN,
 * which the API answers with 503 and {@code Retry-After} (plan 5's end-of-plan review, I1). A read
 * must not run inside another transaction: it would join it, lose its own snapshot, and bound the
 * rest of that transaction's statements at 2 s.
 */
final class BoundedSnapshot {

  private static final Logger LOG = LoggerFactory.getLogger(BoundedSnapshot.class);

  private final TransactionTemplate snapshot;
  private final JdbcClient jdbc;

  BoundedSnapshot(DataSourceTransactionManager transactions) {
    this.snapshot = new TransactionTemplate(transactions);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.jdbc =
        JdbcClient.create(requireNonNull(transactions.getDataSource(), "the manager's DataSource"));
  }

  /** The statements of one read, in one bounded snapshot. */
  <T> T read(Supplier<T> statements) {
    try {
      return snapshot.execute(
          status -> {
            StatementTimeout.SEARCH_READ.boundTheTransaction(jdbc);
            return statements.get();
          });
    } catch (QueryTimeoutException e) {
      LOG.warn("A read of the inventory outran its bound and was cancelled", e);
      throw new InventoryUnavailableException("A read of the inventory outran its bound", e);
    }
  }
}
