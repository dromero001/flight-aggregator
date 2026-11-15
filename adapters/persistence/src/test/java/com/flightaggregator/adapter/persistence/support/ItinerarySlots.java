package com.flightaggregator.adapter.persistence.support;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The two itinerary slots of the test database, as the tests that load and read them see them. */
public final class ItinerarySlots {

  private ItinerarySlots() {}

  /**
   * Both slots empty with only their DEFAULT partitions, and generation 0 active, as on a fresh
   * database. Another class's tests may leave rows, day partitions, or a partition the repository
   * did not name, which would fail the next load.
   */
  public static void empty(JdbcClient jdbc) {
    jdbc.sql("TRUNCATE itinerary_a, itinerary_b").update();
    for (String slot : List.of("itinerary_a", "itinerary_b")) {
      partitions(jdbc, slot).stream()
          .filter(partition -> !partition.endsWith("_default"))
          .forEach(partition -> jdbc.sql("DROP TABLE " + partition).update());
    }
    jdbc.sql(
            "UPDATE inventory_publication SET active_slot = 'a', generation = 0, published_at = NULL")
        .update();
  }

  /** The partitions of a slot, by name, its DEFAULT partition included. */
  public static List<String> partitions(JdbcClient jdbc, String slot) {
    return jdbc.sql(
            "SELECT relid::text FROM pg_partition_tree(CAST(? AS regclass)) WHERE isleaf"
                + " ORDER BY 1")
        .param(slot)
        .query(String.class)
        .list();
  }
}
