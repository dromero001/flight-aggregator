package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.InventoryState;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one owner of {@code inventory_publication}'s SQL (§6.5): the row that names the slot search
 * reads, its generation and when a run last published it. Each statement runs in the caller's
 * transaction, and each lock it takes is held until that transaction ends.
 */
final class PublicationRow {

  /**
   * PostgreSQL's {@code lock_not_available}, which {@code NOWAIT} raises at once and {@code
   * lock_timeout} at its end. Spring leaves it uncategorized, so it is read from the driver's
   * exception.
   */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  private static final String ACTIVE_SLOT = "SELECT active_slot FROM inventory_publication";

  private final JdbcClient jdbc;

  PublicationRow(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The slot search reads, and its state. */
  record Active(Slot slot, InventoryState state) {}

  /** The row as a read sees it, with no lock. */
  Active read() {
    return jdbc.sql("SELECT active_slot, generation, published_at FROM inventory_publication")
        .query(
            (rs, rowNum) ->
                new Active(
                    Slot.of(rs.getString("active_slot")),
                    new InventoryState(
                        rs.getLong("generation"), ProviderTime.readInstant(rs, "published_at"))))
        .single();
  }

  /**
   * The active slot, read under the row's lock, held until the run or the refresh commits or rolls
   * back: a second run, or a refresh, at once waits here, before it touches either slot, and then
   * reads the slot the first one left active. Without the lock a run could read the old active
   * slot, and truncate the new one once the first run switched to it; a refresh could write into
   * the slot a run is about to leave.
   */
  Slot activeLocked() {
    return Slot.of(jdbc.sql(ACTIVE_SLOT + " FOR UPDATE").query(String.class).single());
  }

  /**
   * The active slot, read under the row's lock as {@link #activeLocked} reads it, once a run has
   * published it. Before the first run ({@code published_at} unset) there is nothing to rewrite,
   * and a refresh must not publish a generation no run made.
   */
  Slot publishedLocked() {
    record Row(String activeSlot, boolean published) {}
    Row row =
        jdbc.sql(
                "SELECT active_slot, published_at IS NOT NULL AS published"
                    + " FROM inventory_publication FOR UPDATE")
            .query(Row.class)
            .single();
    if (!row.published()) {
      throw new IllegalStateException("No run has published the inventory yet: nothing to refresh");
    }
    return Slot.of(row.activeSlot());
  }

  /**
   * The active slot, read under a share of the row's lock, held until the update commits; none,
   * without waiting, while a run holds the row {@code FOR UPDATE}. Shares do not exclude each
   * other, so re-prices store side by side, and a run that starts meanwhile waits for them before
   * it touches either slot. Any other failure to read the row propagates.
   */
  Optional<Slot> activeUnlessARunHoldsIt() {
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

  /** The next generation of the same slot, {@code published_at} unchanged, as a refresh makes. */
  long nextGeneration() {
    return jdbc.sql(
            "UPDATE inventory_publication SET generation = generation + 1 RETURNING generation")
        .query(Long.class)
        .single();
  }

  /** Makes the slot the one search reads, in the next generation, and returns that generation. */
  long switchTo(Slot slot, Instant now) {
    return jdbc.sql(
            "UPDATE inventory_publication SET active_slot = ?, generation = generation + 1,"
                + " published_at = ? RETURNING generation")
        .param(slot.code())
        .param(ProviderTime.instantParameter(now))
        .query(Long.class)
        .single();
  }
}
