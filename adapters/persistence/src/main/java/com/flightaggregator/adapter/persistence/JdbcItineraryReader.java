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
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.core.json.JsonFactory;

/**
 * Reads the slot {@code inventory_publication} names as active (§6.5): its generation, a keyset
 * page of its itineraries ({@link KeysetQuery}), or one itinerary by id. Each read is one {@link
 * BoundedSnapshot}, whose snapshot is taken at its first statement: the generation and the rows of
 * a page come from that one snapshot, even when a refresh that rewrites the active slot and bumps
 * the generation in one transaction (Q2-B) commits between the two statements. {@code READ
 * COMMITTED} takes a snapshot per statement and would label the refresh's rows with the generation
 * before it. Its bound means a read never holds its lock on the slot, which a run's {@code
 * TRUNCATE} waits for, past 2 s; a read cut by it fails with the port's {@link
 * InventoryUnavailableException}. Every table name comes from {@link Slot}.
 */
public final class JdbcItineraryReader implements ItineraryReader {

  private final BoundedSnapshot snapshot;
  private final JdbcClient jdbc;
  private final PublicationRow publication;
  private final JsonFactory json;

  /**
   * Its connections and its transactions come from the one manager, as for the stores that write:
   * it cannot be given a second data source.
   */
  public JdbcItineraryReader(DataSourceTransactionManager transactions, JsonFactory json) {
    this.snapshot = new BoundedSnapshot(transactions);
    this.jdbc =
        JdbcClient.create(requireNonNull(transactions.getDataSource(), "the manager's DataSource"));
    this.publication = new PublicationRow(jdbc);
    this.json = json;
  }

  @Override
  public InventoryState state() {
    return snapshot.read(() -> publication.read().state());
  }

  @Override
  public StoredPage page(
      SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant now) {
    return snapshot.read(
        () -> {
          PublicationRow.Active active = publication.read();
          KeysetQuery query = KeysetQuery.of(active.slot(), criteria, after, limit, now);
          return new StoredPage(
              active.state().generation(),
              jdbc.sql(query.sql()).params(query.parameters()).query(this::itinerary).list());
        });
  }

  @Override
  public Optional<Itinerary> byId(ItineraryId id) {
    return snapshot.read(
        () ->
            jdbc.sql("SELECT legs FROM " + publication.read().slot().table() + " WHERE id = ?")
                .param(id.value())
                .query(this::itinerary)
                .optional());
  }

  private Itinerary itinerary(ResultSet rs, int rowNum) throws SQLException {
    return new Itinerary(LegsJson.read(rs.getString("legs"), json));
  }
}
