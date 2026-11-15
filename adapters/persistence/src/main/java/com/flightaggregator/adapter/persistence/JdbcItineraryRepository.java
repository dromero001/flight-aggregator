package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.Publication;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * The itineraries in two slots, {@code itinerary_a} and {@code itinerary_b}, of which {@code
 * inventory_publication} names the one search reads (§6.5). A run is one transaction that empties
 * the other slot, keeps its day partitions from today to today plus {@code partitionDaysAhead},
 * copies the itineraries in with {@code COPY}, and switches the active slot and the generation. A
 * failure anywhere rolls it all back, and the writer's exception propagates as it was thrown. The
 * writer's {@code writeTo} runs inside the load, on the load's connection, and must not use the
 * storage ports. All DDL touches only the inactive slot, and every table name comes from {@link
 * Slot}.
 */
public final class JdbcItineraryRepository implements ItineraryRepository {

  /**
   * How long a run waits for a lock before it fails, for example while a search still reads the
   * inactive slot's previous generation, instead of waiting for ever.
   */
  private static final String LOCK_TIMEOUT = "5s";

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final JsonFactory json;
  private final int partitionDaysAhead;
  private final JdbcClient jdbc;

  public JdbcItineraryRepository(
      DataSource dataSource,
      TransactionTemplate transaction,
      JsonFactory json,
      int partitionDaysAhead) {
    this.dataSource = dataSource;
    this.transaction = transaction;
    this.json = json;
    this.partitionDaysAhead = partitionDaysAhead;
    this.jdbc = JdbcClient.create(dataSource);
  }

  @Override
  public Publication replaceInventory(Instant now, ItineraryWriter writer) {
    return transaction.execute(status -> load(now, writer));
  }

  private Publication load(Instant now, ItineraryWriter writer) {
    jdbc.sql("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'").update();
    Slot slot = activeSlot().other();
    LocalDate today = PartitionCalendar.dayOf(now);
    LocalDate lastPartitionDay = today.plusDays(partitionDaysAhead);
    // Before the partitions are created: PostgreSQL refuses CREATE ... PARTITION OF while the
    // DEFAULT partition holds rows in the new partition's range.
    jdbc.sql("TRUNCATE " + slot.table()).update();
    dropPartitionsBefore(slot, today);
    today.datesUntil(lastPartitionDay.plusDays(1)).forEach(day -> createPartition(slot, day));
    long itineraries = copy(slot, writer);
    long rowsInDefaultPartition =
        jdbc.sql("SELECT count(*) FROM " + slot.defaultPartition()).query(Long.class).single();
    long generation = switchTo(slot, now);
    return new Publication(generation, itineraries, rowsInDefaultPartition, lastPartitionDay);
  }

  private Slot activeSlot() {
    return Slot.of(
        jdbc.sql("SELECT active_slot FROM inventory_publication").query(String.class).single());
  }

  /** Makes the slot the one search reads, in the next generation, and returns that generation. */
  private long switchTo(Slot slot, Instant now) {
    return jdbc.sql(
            "UPDATE inventory_publication SET active_slot = ?, generation = generation + 1,"
                + " published_at = ? RETURNING generation")
        .param(slot.code())
        .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  /**
   * Drops the slot's day partitions before today. Their days are read back from their names, so a
   * partition the repository did not name fails the run instead of being kept or dropped silently.
   */
  private void dropPartitionsBefore(Slot slot, LocalDate today) {
    jdbc
        .sql(
            "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " WHERE i.inhparent = CAST(? AS regclass) AND c.relname <> ?")
        .param(slot.table())
        .param(slot.defaultPartition())
        .query(String.class)
        .list()
        .stream()
        .map(slot::dayOfPartition)
        .filter(day -> day.isBefore(today))
        .forEach(day -> jdbc.sql("DROP TABLE " + slot.partition(day)).update());
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
