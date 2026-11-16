package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * The itineraries in two slots, {@code itinerary_a} and {@code itinerary_b}, of which {@code
 * inventory_publication} names the one search reads (§6.5). A run is one transaction that first
 * locks the publication row, so two runs never load at once, then empties the other slot, keeps its
 * day partitions from today to today plus {@code partitionDaysAhead}, copies the itineraries in
 * with {@code COPY}, analyzes the slot, and switches the active slot and the generation. A failure
 * anywhere rolls it all back, and the writer's exception propagates as it was thrown. The writer's
 * {@code writeTo} runs inside the load, on the load's connection, and must not use the storage
 * ports. All DDL touches only the inactive slot, and every table name comes from {@link Slot}. A
 * stale-route refresh's {@link #replaceThrough} rewrites an origin's rows of the active slot under
 * the same lock, and a run waits up to 60 s for it. A re-price's {@link #updateInPlace} rewrites
 * one row of the active slot under a share of the same lock, which it never waits for.
 */
public final class JdbcItineraryRepository implements ItineraryRepository {

  /**
   * How long a run past the publication row, a refresh, or an update past the publication row,
   * waits for a lock before it fails, for example while a search still reads the inactive slot's
   * previous generation, instead of waiting for ever.
   */
  private static final String LOCK_TIMEOUT = "5s";

  /**
   * How long a run waits for the publication row itself (Q9): a refresh holds it while it deletes
   * and copies an origin's itineraries, which takes seconds, so a run that starts meanwhile waits
   * for it instead of failing at the 5 s its other locks wait.
   */
  private static final String RUN_WAIT_FOR_THE_PUBLICATION = "60s";

  /**
   * PostgreSQL's {@code lock_not_available}, which {@code NOWAIT} raises at once and {@code
   * lock_timeout} at its end. Spring leaves it uncategorized, so it is read from the driver's
   * exception.
   */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /** The publication row's read of the active slot; each caller adds the lock it takes. */
  private static final String ACTIVE_SLOT = "SELECT active_slot FROM inventory_publication";

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final JsonFactory json;
  private final int partitionDaysAhead;
  private final JdbcClient jdbc;

  /**
   * Its connections and its transaction come from the one manager, so the load and its {@code COPY}
   * always run in that manager's transaction: it cannot be given a second data source.
   */
  public JdbcItineraryRepository(
      DataSourceTransactionManager transactions, JsonFactory json, int partitionDaysAhead) {
    if (partitionDaysAhead < 0) {
      throw new IllegalArgumentException(
          "partitionDaysAhead must not be negative: " + partitionDaysAhead);
    }
    this.dataSource = requireNonNull(transactions.getDataSource(), "the manager's DataSource");
    this.transaction = new TransactionTemplate(transactions);
    this.json = json;
    this.partitionDaysAhead = partitionDaysAhead;
    this.jdbc = JdbcClient.create(dataSource);
  }

  @Override
  public Publication replaceInventory(Instant now, ItineraryWriter writer) {
    return transaction.execute(status -> load(now, writer));
  }

  @Override
  public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
    return transaction.execute(status -> rewriteThrough(origin, writer));
  }

  @Override
  public StoreOutcome updateInPlace(Itinerary repriced) {
    return transaction.execute(status -> rewrite(repriced));
  }

  /**
   * Rewrites the itinerary's row in the active slot through {@link ItineraryRow}, the mapping the
   * load uses, so every column derived from the legs follows them: a later arrival also moves the
   * duration a page by duration seeks on. The row is deleted by its key, {@code (id, departure)},
   * and copied back in the same transaction; the id names the legs' flights, their departures
   * included, so a re-quote has the stored row's key. The active slot is read under a share of the
   * publication row's lock, held until the commit, so a run cannot switch slots between the read
   * and the write. While a run holds the row, nothing is written: the update answers busy at once
   * instead of holding a connection for the run's whole load. Its other lock waits and its
   * statements are bounded as the load's are.
   */
  private StoreOutcome rewrite(Itinerary repriced) {
    waitForEachLockAtMost(LOCK_TIMEOUT);
    StatementTimeout.STORE.boundTheTransaction(jdbc);
    Optional<Slot> active = activeSlotUnlessARunHoldsIt();
    if (active.isEmpty()) {
      // The refused lock aborted the transaction; its commit is a rollback.
      return StoreOutcome.BUSY;
    }
    Slot slot = active.get();
    int deleted =
        jdbc.sql("DELETE FROM " + slot.table() + " WHERE id = ? AND departure = ?")
            .param(repriced.id().value())
            .param(ProviderTime.instantParameter(repriced.departure().toInstant()))
            .update();
    if (deleted == 0) {
      return StoreOutcome.GONE;
    }
    copy(slot, sink -> sink.accept(repriced));
    return StoreOutcome.STORED;
  }

  /**
   * Rewrites, in the active slot, the itineraries with a leg departing from the origin: deletes
   * them, copies the writer's in through the load's own {@code COPY} and {@link ItineraryRow}, and
   * moves the generation on, in one transaction, so search reads the old rows or the new ones,
   * never a mix, and a failure leaves the slot as it was. The active slot is read under the
   * publication row's lock, held until the commit, as a run's load holds it: a run cannot switch
   * slots between the read and the write, and a run that starts meanwhile waits for the commit.
   * {@code published_at} stays the run's, and so do the slot's day partitions; the {@link
   * Publication} reports where they end and what the {@code DEFAULT} partition holds after the
   * rewrite. Its lock waits and statements are bounded as the load's are.
   */
  private Publication rewriteThrough(Airport origin, ItineraryWriter writer) {
    waitForEachLockAtMost(LOCK_TIMEOUT);
    StatementTimeout.STORE.boundTheTransaction(jdbc);
    Slot slot = activeSlotLocked();
    Instant partitionedUntil = partitionedUntil(slot);
    jdbc.sql(
            "DELETE FROM "
                + slot.table()
                + " WHERE legs @> jsonb_build_array(jsonb_build_object('origin', ?))")
        .param(origin.code())
        .update();
    long itineraries = copy(slot, writer);
    long generation =
        jdbc.sql(
                "UPDATE inventory_publication SET generation = generation + 1"
                    + " RETURNING generation")
            .query(Long.class)
            .single();
    return new Publication(generation, itineraries, rowsInDefaultPartition(slot), partitionedUntil);
  }

  /**
   * Where the slot's day partitions end, as the run that loaded it created them. A slot no run has
   * loaded has none, and nothing to rewrite.
   */
  private Instant partitionedUntil(Slot slot) {
    LocalDate lastDay =
        dayPartitions(slot).stream()
            .max(Comparator.naturalOrder())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        slot.table() + " has no day partition: no run has published into it"));
    return PartitionCalendar.start(lastDay.plusDays(1));
  }

  private Publication load(Instant now, ItineraryWriter writer) {
    waitForEachLockAtMost(RUN_WAIT_FOR_THE_PUBLICATION);
    StatementTimeout.STORE.boundTheTransaction(jdbc);
    Slot slot = activeSlotLocked().other();
    waitForEachLockAtMost(LOCK_TIMEOUT);
    LocalDate today = PartitionCalendar.dayOf(now);
    LocalDate lastPartitionDay = today.plusDays(partitionDaysAhead);
    // Before the partitions are created: PostgreSQL refuses CREATE ... PARTITION OF while the
    // DEFAULT partition holds rows in the new partition's range.
    jdbc.sql("TRUNCATE " + slot.table()).update();
    dropPartitionsBefore(slot, today);
    today.datesUntil(lastPartitionDay.plusDays(1)).forEach(day -> createPartition(slot, day));
    long itineraries = copy(slot, writer);
    long rowsInDefaultPartition = rowsInDefaultPartition(slot);
    // Before the switch, so search never reads the slot without statistics. ANALYZE of the parent
    // also analyzes each partition (its row count and its column statistics), which the planner
    // reads for each partition's scan under the Merge Append.
    jdbc.sql("ANALYZE " + slot.table()).update();
    long generation = switchTo(slot, now);
    return new Publication(
        generation,
        itineraries,
        rowsInDefaultPartition,
        PartitionCalendar.start(lastPartitionDay.plusDays(1)));
  }

  /**
   * The active slot, read under the publication row's lock, held until the run or the refresh
   * commits or rolls back: a second run, or a refresh, at once waits here, before it touches either
   * slot, and then reads the slot the first one left active. Without the lock a run could read the
   * old active slot, and truncate the new one once the first run switched to it; a refresh could
   * write into the slot a run is about to leave.
   */
  private Slot activeSlotLocked() {
    return Slot.of(jdbc.sql(ACTIVE_SLOT + " FOR UPDATE").query(String.class).single());
  }

  /**
   * The active slot, read under a share of the publication row's lock, held until the update
   * commits; none, without waiting, while a run holds the row {@code FOR UPDATE}. Shares do not
   * exclude each other, so re-prices store side by side, and a run that starts meanwhile waits for
   * them before it touches either slot. Any other failure to read the row propagates.
   */
  private Optional<Slot> activeSlotUnlessARunHoldsIt() {
    try {
      return Optional.of(
          Slot.of(jdbc.sql(ACTIVE_SLOT + " FOR SHARE NOWAIT").query(String.class).single()));
    } catch (DataAccessException e) {
      if (e.getMostSpecificCause() instanceof SQLException refused
          && LOCK_NOT_AVAILABLE.equals(refused.getSQLState())) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /** Makes the slot the one search reads, in the next generation, and returns that generation. */
  private long switchTo(Slot slot, Instant now) {
    return jdbc.sql(
            "UPDATE inventory_publication SET active_slot = ?, generation = generation + 1,"
                + " published_at = ? RETURNING generation")
        .param(slot.code())
        .param(ProviderTime.instantParameter(now))
        .query(Long.class)
        .single();
  }

  /** Bounds each lock wait of the rest of the transaction, and of it alone. */
  private void waitForEachLockAtMost(String timeout) {
    jdbc.sql("SET LOCAL lock_timeout = '" + timeout + "'").update();
  }

  /** Drops the slot's day partitions before today. */
  private void dropPartitionsBefore(Slot slot, LocalDate today) {
    dayPartitions(slot).stream()
        .filter(day -> day.isBefore(today))
        .forEach(day -> jdbc.sql("DROP TABLE " + slot.partition(day)).update());
  }

  /**
   * The days of the slot's day partitions. They are read back from the partitions' names, so a
   * partition the repository did not name fails the caller instead of being kept, dropped or
   * counted silently.
   */
  private List<LocalDate> dayPartitions(Slot slot) {
    return jdbc
        .sql(
            "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " WHERE i.inhparent = CAST(? AS regclass) AND c.relname <> ?")
        .param(slot.table())
        .param(slot.defaultPartition())
        .query(String.class)
        .list()
        .stream()
        .map(slot::dayOfPartition)
        .toList();
  }

  private long rowsInDefaultPartition(Slot slot) {
    return jdbc.sql("SELECT count(*) FROM " + slot.defaultPartition()).query(Long.class).single();
  }

  private void createPartition(Slot slot, LocalDate day) {
    jdbc.sql(
            "CREATE TABLE IF NOT EXISTS "
                + slot.partition(day)
                + " PARTITION OF "
                + slot.table()
                + " FOR VALUES FROM ('"
                + PartitionCalendar.start(day)
                + "') TO ('"
                + PartitionCalendar.start(day.plusDays(1))
                + "')")
        .update();
  }

  private long copy(Slot slot, ItineraryWriter writer) {
    try (CopyRows rows =
        CopyRows.open(
            dataSource, "COPY " + slot.table() + " (" + ItineraryRow.COLUMNS + ") FROM STDIN")) {
      writer.writeTo(itinerary -> rows.row(ItineraryRow.of(itinerary).cells(json)));
      return rows.finish();
    }
  }
}
