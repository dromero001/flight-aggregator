package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.partitions;
import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.published;
import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.rows;
import static com.flightaggregator.adapter.persistence.support.LockWaits.awaitAStatementBlockedBy;
import static com.flightaggregator.adapter.persistence.support.Trips.direct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.flightaggregator.adapter.persistence.support.ItinerarySlots;
import com.flightaggregator.adapter.persistence.support.ItinerarySlots.Published;
import com.flightaggregator.adapter.persistence.support.OneSession;
import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Itinerary;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.json.JsonFactory;

/**
 * A run's load into the inactive slot and its switch. The re-price's update in place is {@link
 * JdbcItineraryRepositoryUpdateTest}'s.
 */
class JdbcItineraryRepositoryLoadTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final JdbcItineraryRepository repository =
      new JdbcItineraryRepository(
          new DataSourceTransactionManager(dataSource), new JsonFactory(), 37);

  @BeforeEach
  void startFromAFreshDatabase() {
    ItinerarySlots.empty(jdbc);
  }

  private Publication publish(Instant now, List<Itinerary> itineraries) {
    return repository.replaceInventory(now, sink -> itineraries.forEach(sink));
  }

  /** A negative horizon would create no day partition, and every itinerary would go to DEFAULT. */
  @Test
  void refusesANegativePartitionHorizon() {
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);

    assertThatThrownBy(() -> new JdbcItineraryRepository(transactions, new JsonFactory(), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("partitionDaysAhead must not be negative: -1");
  }

  @Test
  void publishesIntoTheInactiveSlotAndSwitches() {
    Publication publication =
        publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));

    assertThat(publication)
        .isEqualTo(new Publication(1, 2, 0, Instant.parse("2026-12-28T00:00:00Z")));
    assertThat(published(jdbc)).isEqualTo(new Published("b", 1, NOW));
    assertThat(rows(jdbc, "itinerary_b")).isEqualTo(2);
  }

  /**
   * The planner's statistics are there before search reads the slot the switch publishes: the
   * ANALYZE runs inside the load's transaction, with the switch, so both become visible at its
   * commit. Their order inside it cannot be seen, so the name promises only "in the load".
   */
  @Test
  void analyzesTheLoadedSlotInTheLoad() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));

    // A partition never analyzed reports -1.
    float estimatedRows =
        jdbc.sql("SELECT reltuples FROM pg_class WHERE relname = 'itinerary_b_20261125'")
            .query(Float.class)
            .single();
    assertThat(estimatedRows).isEqualTo(2f);
  }

  /**
   * The key columns of every index of a table but its primary key, e.g. {@code price, id}, in no
   * particular order.
   */
  private List<String> sortIndexes(String table) {
    return jdbc.sql(
            "SELECT substring(indexdef FROM 'USING btree \\((.*)\\)') FROM pg_indexes"
                + " WHERE tablename = ? AND indexname NOT LIKE '%pkey'")
        .param(table)
        .query(String.class)
        .list();
  }

  /**
   * Q6: each sort order, keyed by id, alone and behind an origin and destination prefix, on both
   * slots and on each kind of partition: a day partition created by a load inherits them, and the
   * DEFAULT partitions, which existed when the indexes were created, received them then.
   */
  @Test
  void indexesEverySortOrderOnBothSlots() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z")));

    List<String> expected =
        List.of(
            "departure, id",
            "duration_seconds, id",
            "origin, destination, departure, id",
            "origin, destination, duration_seconds, id",
            "origin, destination, price, id",
            "price, id");
    assertThat(sortIndexes("itinerary_a")).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(sortIndexes("itinerary_b")).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(sortIndexes("itinerary_b_20261125")).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(sortIndexes("itinerary_a_default")).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(sortIndexes("itinerary_b_default")).containsExactlyInAnyOrderElementsOf(expected);
  }

  /**
   * Two runs at once (§6.5): while one holds the publication row, the other waits for it before it
   * reads the active slot or touches either slot, and loads once the first has ended. Without that
   * wait it would read the slot the first run is about to publish and truncate it after the switch.
   * It waits up to 60 s, as for a refresh (Q9), so the test ends the first run rather than waiting
   * for the second to give up.
   */
  @Test
  void aSecondLoadTouchesNoSlotWhileAnotherHoldsThePublication() throws Exception {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z")));
    AtomicBoolean written = new AtomicBoolean();
    CompletableFuture<Publication> secondRun;
    try (Connection firstRun = dataSource.getConnection()) {
      firstRun.setAutoCommit(false);
      int firstBackend;
      try (Statement statement = firstRun.createStatement()) {
        statement.execute("SELECT active_slot FROM inventory_publication FOR UPDATE");
        try (ResultSet backend = statement.executeQuery("SELECT pg_backend_pid()")) {
          backend.next();
          firstBackend = backend.getInt(1);
        }
      }
      secondRun =
          CompletableFuture.supplyAsync(
              () -> repository.replaceInventory(NOW, sink -> written.set(true)));

      awaitAStatementBlockedBy(jdbc, firstBackend);

      assertThat(written).isFalse();
      assertThat(rows(jdbc, "itinerary_b")).isEqualTo(1);
      firstRun.rollback();
    }
    assertThat(secondRun.get(10, TimeUnit.SECONDS).generation()).isEqualTo(2);
  }

  /**
   * A statement of the load that hangs is cut after 5 minutes, and the run fails and rolls back.
   * The bound is the load's alone: after the commit, in the same session, it is gone.
   */
  @Test
  void boundsEachStatementOfTheLoad() {
    AtomicReference<String> statementTimeout = new AtomicReference<>();
    try (OneSession session = new OneSession()) {
      JdbcItineraryRepository sessionRepository =
          new JdbcItineraryRepository(
              new DataSourceTransactionManager(session.dataSource()), new JsonFactory(), 37);

      sessionRepository.replaceInventory(
          NOW,
          sink -> {
            // The writer runs during the COPY, so the setting is read just before the commit, on
            // the load's connection, once the COPY has ended.
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void beforeCommit(boolean readOnly) {
                    statementTimeout.set(session.show("statement_timeout"));
                  }
                });
            sink.accept(direct(1, "2026-11-25T07:15:00Z"));
          });

      assertThat(statementTimeout).hasValue("5min");
      assertThat(session.show("statement_timeout")).isEqualTo("0");
    }
  }

  @Test
  void rebuildsTheOtherSlotOnTheNextRun() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));

    Publication publication = publish(NOW, List.of(direct(3, "2026-11-25T09:15:00Z")));

    assertThat(publication.generation()).isEqualTo(2);
    assertThat(published(jdbc)).isEqualTo(new Published("a", 2, NOW));
    assertThat(rows(jdbc, "itinerary_a")).isEqualTo(1);
    // Generation 1 is still whole, so a search that read it can finish.
    assertThat(rows(jdbc, "itinerary_b")).isEqualTo(2);
  }

  @Test
  void replacesTheRowsOfTheSlotItLoads() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));
    publish(NOW, List.of(direct(3, "2026-11-25T09:15:00Z")));

    publish(NOW, List.of(direct(4, "2026-11-25T10:15:00Z")));

    assertThat(published(jdbc).activeSlot()).isEqualTo("b");
    assertThat(rows(jdbc, "itinerary_b")).isEqualTo(1);
  }

  @Test
  void aFailureMidLoadKeepsThePreviousGeneration() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));
    publish(NOW, List.of(direct(3, "2026-11-25T09:15:00Z")));
    RuntimeException failure = new IllegalStateException("the writer failed");

    assertThatThrownBy(
            () ->
                repository.replaceInventory(
                    NOW,
                    sink -> {
                      for (int number = 1; number <= 1_000; number++) {
                        sink.accept(direct(number, "2026-11-26T07:15:00Z"));
                      }
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(published(jdbc)).isEqualTo(new Published("a", 2, NOW));
    // The TRUNCATE of slot b rolled back with the load.
    assertThat(rows(jdbc, "itinerary_b")).isEqualTo(2);
  }

  @Test
  void dropsPartitionsBeforeTodayAndCreatesThemAhead() {
    publish(Instant.parse("2026-11-18T10:00:00Z"), List.of(direct(1, "2026-11-25T07:15:00Z")));
    publish(Instant.parse("2026-11-19T10:00:00Z"), List.of(direct(2, "2026-11-25T07:15:00Z")));

    publish(NOW, List.of(direct(3, "2026-11-25T07:15:00Z")));

    // 2026-11-20 to 2026-12-27 is 38 days, so 38 distinct names from the first to the last are
    // every day in between.
    assertThat(partitions(jdbc, "itinerary_b"))
        .hasSize(39)
        .doesNotHaveDuplicates()
        .startsWith("itinerary_b_20261120")
        .endsWith("itinerary_b_20261227", "itinerary_b_default");
    // The DDL touched only the slot it loaded.
    assertThat(partitions(jdbc, "itinerary_a")).startsWith("itinerary_a_20261119");
  }

  @Test
  void putsADepartureBeyondThePartitionsInTheDefaultPartition() {
    Publication publication =
        publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-12-30T07:15:00Z")));

    assertThat(publication)
        .isEqualTo(new Publication(1, 2, 1, Instant.parse("2026-12-28T00:00:00Z")));
    assertThat(published(jdbc)).isEqualTo(new Published("b", 1, NOW));
  }

  @Test
  void loadsAThousandItinerariesThroughCopy() {
    List<Itinerary> itineraries =
        IntStream.rangeClosed(1, 1_000)
            .mapToObj(number -> direct(number, "2026-11-25T08:15:00+01:00"))
            .toList();

    Publication publication = publish(NOW, itineraries);

    assertThat(publication.itineraries()).isEqualTo(1_000);
    assertThat(rows(jdbc, "itinerary_b")).isEqualTo(1_000);
    assertThat(
            jdbc.sql(
                    "SELECT departure, departure_offset, arrival, arrival_offset, origin,"
                        + " destination, duration_seconds, stops, single_carrier, price, quoted_at,"
                        + " legs -> 0 ->> 'flightNumber' AS flight_number"
                        + " FROM itinerary_b WHERE id = ?")
                .param(itineraries.get(499).id().value())
                .query(
                    (rs, rowNum) ->
                        List.of(
                            rs.getObject("departure", OffsetDateTime.class).toInstant(),
                            rs.getInt("departure_offset"),
                            rs.getObject("arrival", OffsetDateTime.class).toInstant(),
                            rs.getInt("arrival_offset"),
                            rs.getString("origin"),
                            rs.getString("destination"),
                            rs.getInt("duration_seconds"),
                            rs.getInt("stops"),
                            rs.getString("single_carrier"),
                            rs.getBigDecimal("price"),
                            rs.getObject("quoted_at", OffsetDateTime.class).toInstant(),
                            rs.getString("flight_number")))
                .single())
        .containsExactly(
            Instant.parse("2026-11-25T07:15:00Z"),
            3600,
            Instant.parse("2026-11-25T09:15:00Z"),
            3600,
            "MAD",
            "LHR",
            7200,
            0,
            "IB",
            new BigDecimal("105.00"),
            Instant.parse("2026-11-15T10:00:00Z"),
            "500");
  }

  @Test
  void givesUpWhenTheInactiveSlotStaysLocked() throws SQLException {
    try (Connection search = dataSource.getConnection()) {
      // A search still reading slot b, which the run must truncate.
      search.setAutoCommit(false);
      try (Statement statement = search.createStatement()) {
        statement.execute("LOCK TABLE itinerary_b IN ACCESS SHARE MODE");
      }

      Throwable failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(15),
              () -> catchThrowable(() -> publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z")))));

      // 55P03 is PostgreSQL's lock_not_available, which lock_timeout raises.
      assertThat(failure)
          .rootCause()
          .isInstanceOfSatisfying(
              SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo("55P03"));

      search.rollback();
    }
    assertThat(published(jdbc)).isEqualTo(new Published("a", 0, null));
  }

  /**
   * A name with another word, one shorter than the slot's prefix, and eight digits that are no day.
   */
  @ParameterizedTest
  @ValueSource(strings = {"itinerary_b_archive", "itin", "itinerary_b_20261399"})
  void failsOnAPartitionOfTheSlotItDidNotName(String partition) {
    jdbc.sql(
            "CREATE TABLE "
                + partition
                + " PARTITION OF itinerary_b"
                + " FOR VALUES FROM ('2026-01-01T00:00:00Z') TO ('2026-02-01T00:00:00Z')")
        .update();

    assertThatThrownBy(() -> publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("itinerary_b has a partition the repository did not create: " + partition);

    assertThat(published(jdbc)).isEqualTo(new Published("a", 0, null));
    assertThat(partitions(jdbc, "itinerary_b")).contains(partition);
  }
}
