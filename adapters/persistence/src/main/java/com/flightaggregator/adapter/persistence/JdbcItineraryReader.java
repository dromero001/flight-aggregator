package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.in.InventoryUnavailableException;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.StoredPage;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * Reads the slot {@code inventory_publication} names as active (§6.5): its generation, a keyset
 * page of its itineraries ({@link KeysetQuery}), or one itinerary by id. Each read is one read-only
 * transaction at {@code REPEATABLE READ}, whose snapshot is taken at its first statement: the
 * generation and the rows of a page come from that one snapshot, even when a refresh that rewrites
 * the active slot and bumps the generation in one transaction (Q2-B) commits between the two
 * statements. {@code READ COMMITTED} takes a snapshot per statement and would label the refresh's
 * rows with the generation before it. Each read's statements are bounded by {@link
 * StatementTimeout#SEARCH_READ}: a read that takes longer rolls back, so it never holds its
 * connection, or its lock on the slot that a run's {@code TRUNCATE} waits for, past the bound, and
 * fails with the port's {@link InventoryUnavailableException}, logged at WARN, which the API
 * answers with 503 and {@code Retry-After} (plan 5's end-of-plan review, I1). A read must not run
 * inside another transaction: it would join it, lose its own snapshot, and bound the rest of that
 * transaction's statements at 2 s. Every table name comes from {@link Slot}.
 */
public final class JdbcItineraryReader implements ItineraryReader {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcItineraryReader.class);

  private final TransactionTemplate snapshot;
  private final JdbcClient jdbc;
  private final JsonFactory json;

  /**
   * Its connections and its transactions come from the one manager, as for the stores that write:
   * it cannot be given a second data source.
   */
  public JdbcItineraryReader(DataSourceTransactionManager transactions, JsonFactory json) {
    this.snapshot = new TransactionTemplate(transactions);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.jdbc =
        JdbcClient.create(requireNonNull(transactions.getDataSource(), "the manager's DataSource"));
    this.json = json;
  }

  @Override
  public InventoryState state() {
    return read(() -> active().state());
  }

  @Override
  public StoredPage page(
      SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {
    return read(
        () -> {
          Active active = active();
          KeysetQuery query = KeysetQuery.of(active.slot(), criteria, after, limit, now);
          return new StoredPage(
              active.state().generation(),
              jdbc.sql(query.sql()).params(query.parameters()).query(this::itinerary).list());
        });
  }

  @Override
  public Optional<Itinerary> byId(ItineraryId id) {
    return read(
        () ->
            jdbc.sql("SELECT legs FROM " + active().slot().table() + " WHERE id = ?")
                .param(id.value())
                .query(this::itinerary)
                .optional());
  }

  /** The statements of one read, in one bounded snapshot. */
  private <T> T read(Supplier<T> statements) {
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

  private Itinerary itinerary(ResultSet rs, int rowNum) throws SQLException {
    return new Itinerary(LegsJson.read(rs.getString("legs"), json));
  }

  /** The {@code inventory_publication} row: the slot search reads, and its state. */
  private record Active(Slot slot, InventoryState state) {}

  private Active active() {
    return jdbc.sql("SELECT active_slot, generation, published_at FROM inventory_publication")
        .query(
            (rs, rowNum) ->
                new Active(
                    Slot.of(rs.getString("active_slot")),
                    new InventoryState(
                        rs.getLong("generation"),
                        Optional.ofNullable(rs.getObject("published_at", OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant))))
        .single();
  }
}
