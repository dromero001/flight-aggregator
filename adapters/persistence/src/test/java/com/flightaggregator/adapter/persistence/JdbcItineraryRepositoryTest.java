package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.partitions;
import static com.flightaggregator.application.port.in.SortOrder.DURATION;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.flightaggregator.adapter.persistence.support.ItinerarySlots;
import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

class JdbcItineraryRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  /** When the re-quotes of the update tests were quoted. */
  private static final Instant REQUOTED_AT = Instant.parse("2026-11-20T10:40:00Z");

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final DataSourceTransactionManager transactions =
      new DataSourceTransactionManager(dataSource);
  private final JdbcItineraryRepository repository =
      new JdbcItineraryRepository(transactions, new JsonFactory(), 37);

  /**
   * A transaction of the repository's manager. An update inside it joins it, so the test can read
   * the update's settings, or keep its locks, before the commit.
   */
  private final TransactionTemplate enclosing = new TransactionTemplate(transactions);

  private final JdbcItineraryReader reader =
      new JdbcItineraryReader(new DataSourceTransactionManager(dataSource), new JsonFactory());

  @BeforeEach
  void startFromAFreshDatabase() {
    ItinerarySlots.empty(jdbc);
  }

  /** {@code IB<number>} from Madrid to London, departing at {@code departure} for two hours. */
  private static Itinerary direct(int number, String departure) {
    return direct(number, departure, Duration.ofHours(2));
  }

  private static Itinerary direct(int number, String departure, Duration flying) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Itinerary(
        List.of(
            new Leg(
                new Carrier("IB"),
                number,
                new Airport("MAD"),
                new Airport("LHR"),
                departsAt,
                departsAt.plus(flying),
                Money.euros("100.00"),
                ALPHA,
                Instant.parse("2026-11-15T10:00:00Z"))));
  }

  private Publication publish(Instant now, List<Itinerary> itineraries) {
    return repository.replaceInventory(now, sink -> itineraries.forEach(sink));
  }

  private record Published(String activeSlot, long generation, Instant publishedAt) {}

  private Published published() {
    return jdbc.sql("SELECT active_slot, generation, published_at FROM inventory_publication")
        .query(
            (rs, rowNum) -> {
              OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
              return new Published(
                  rs.getString("active_slot"),
                  rs.getLong("generation"),
                  publishedAt == null ? null : publishedAt.toInstant());
            })
        .single();
  }

  private long rows(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
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
    assertThat(published()).isEqualTo(new Published("b", 1, NOW));
    assertThat(rows("itinerary_b")).isEqualTo(2);
  }

  /** The planner's statistics must be there before search reads the slot the switch publishes. */
  @Test
  void analyzesTheLoadedSlotBeforeTheSwitch() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));

    // A partition never analyzed reports -1.
    float estimatedRows =
        jdbc.sql("SELECT reltuples FROM pg_class WHERE relname = 'itinerary_b_20261125'")
            .query(Float.class)
            .single();
    assertThat(estimatedRows).isEqualTo(2f);
  }

  /** The key columns of every index of a table but its primary key, e.g. {@code price, id}. */
  private List<String> sortIndexes(String table) {
    return jdbc.sql(
            "SELECT substring(indexdef FROM 'USING btree \\((.*)\\)') FROM pg_indexes"
                + " WHERE tablename = ? AND indexname NOT LIKE '%pkey' ORDER BY 1")
        .param(table)
        .query(String.class)
        .list();
  }

  /** Q6: each sort order, keyed by id, alone and behind an origin and destination prefix. */
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
    assertThat(sortIndexes("itinerary_a")).isEqualTo(expected);
    assertThat(sortIndexes("itinerary_b")).isEqualTo(expected);
    assertThat(sortIndexes("itinerary_b_20261125")).isEqualTo(expected);
  }

  /**
   * Two runs at once (§6.5): while one holds the publication row, the other waits for it before it
   * reads the active slot or touches either slot, and gives up at the lock timeout. Without that
   * wait it would read the slot the first run is about to publish and truncate it after the switch.
   */
  @Test
  void aSecondLoadTouchesNoSlotWhileAnotherHoldsThePublication() throws SQLException {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z")));
    try (Connection firstRun = dataSource.getConnection()) {
      firstRun.setAutoCommit(false);
      try (Statement statement = firstRun.createStatement()) {
        statement.execute("SELECT active_slot FROM inventory_publication FOR UPDATE");
      }
      AtomicBoolean written = new AtomicBoolean();

      Throwable failure =
          catchThrowable(() -> repository.replaceInventory(NOW, sink -> written.set(true)));

      firstRun.rollback();
      assertThat(failure).hasStackTraceContaining("lock timeout");
      assertThat(written).isFalse();
    }
    assertThat(rows("itinerary_b")).isEqualTo(1);
  }

  /**
   * A statement of the load that hangs is cut after 5 minutes, and the run fails and rolls back.
   */
  @Test
  void boundsEachStatementOfTheLoad() {
    AtomicReference<String> statementTimeout = new AtomicReference<>();

    repository.replaceInventory(
        NOW,
        sink -> {
          // The writer runs during the COPY, so the setting is read just before the commit, on the
          // load's connection, once the COPY has ended.
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                  statementTimeout.set(
                      jdbc.sql("SHOW statement_timeout").query(String.class).single());
                }
              });
          sink.accept(direct(1, "2026-11-25T07:15:00Z"));
        });

    assertThat(statementTimeout).hasValue("5min");
    assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("0");
  }

  @Test
  void rebuildsTheOtherSlotOnTheNextRun() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));

    Publication publication = publish(NOW, List.of(direct(3, "2026-11-25T09:15:00Z")));

    assertThat(publication.generation()).isEqualTo(2);
    assertThat(published()).isEqualTo(new Published("a", 2, NOW));
    assertThat(rows("itinerary_a")).isEqualTo(1);
    // Generation 1 is still whole, so a search that read it can finish.
    assertThat(rows("itinerary_b")).isEqualTo(2);
  }

  @Test
  void replacesTheRowsOfTheSlotItLoads() {
    publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));
    publish(NOW, List.of(direct(3, "2026-11-25T09:15:00Z")));

    publish(NOW, List.of(direct(4, "2026-11-25T10:15:00Z")));

    assertThat(published().activeSlot()).isEqualTo("b");
    assertThat(rows("itinerary_b")).isEqualTo(1);
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

    assertThat(published()).isEqualTo(new Published("a", 2, NOW));
    // The TRUNCATE of slot b rolled back with the load.
    assertThat(rows("itinerary_b")).isEqualTo(2);
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
    assertThat(published()).isEqualTo(new Published("b", 1, NOW));
  }

  @Test
  void loadsAThousandItinerariesThroughCopy() {
    List<Itinerary> itineraries =
        IntStream.rangeClosed(1, 1_000)
            .mapToObj(number -> direct(number, "2026-11-25T08:15:00+01:00"))
            .toList();

    Publication publication = publish(NOW, itineraries);

    assertThat(publication.itineraries()).isEqualTo(1_000);
    assertThat(rows("itinerary_b")).isEqualTo(1_000);
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
    assertThat(published()).isEqualTo(new Published("a", 0, null));
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

    assertThat(published()).isEqualTo(new Published("a", 0, null));
    assertThat(partitions(jdbc, "itinerary_b")).contains(partition);
  }

  /** The itinerary's one flight as another provider sells it now, landing {@code arrival}. */
  private static Itinerary requoted(
      Itinerary itinerary, OffsetDateTime arrival, String euros, Instant quotedAt) {
    Leg leg = itinerary.legs().getFirst();
    return new Itinerary(
        List.of(
            new Leg(
                leg.carrier(),
                leg.flightNumber(),
                leg.origin(),
                leg.destination(),
                leg.departure(),
                arrival,
                Money.euros(euros),
                BETA,
                quotedAt)));
  }

  /** The itinerary's one flight as Beta sells it now at that price, landing as stored. */
  private static Itinerary requoted(Itinerary itinerary, String euros) {
    return requoted(itinerary, itinerary.arrival(), euros, REQUOTED_AT);
  }

  private BigDecimal storedPrice(String slot, Itinerary itinerary) {
    return jdbc.sql("SELECT price FROM " + slot + " WHERE id = ?")
        .param(itinerary.id().value())
        .query(BigDecimal.class)
        .single();
  }

  /**
   * Q5: the active generation's row takes the re-quote, and the generation, its publication time
   * and the other rows stay. The previous generation's slot, still whole for a search reading it,
   * keeps the row it had.
   */
  @Test
  void updatesAnItineraryInPlaceWithoutChangingTheGeneration() {
    Itinerary chosen = direct(1, "2026-11-25T07:15:00Z");
    Itinerary other = direct(2, "2026-11-25T08:15:00Z");
    publish(NOW, List.of(chosen, other));
    Instant secondRun = NOW.plusSeconds(1_800);
    publish(secondRun, List.of(chosen, other));
    Instant quotedAt = Instant.parse("2026-11-20T10:40:00Z");
    Itinerary repriced = requoted(chosen, chosen.arrival(), "120.00", quotedAt);

    StoreOutcome outcome = repository.updateInPlace(repriced);

    assertThat(outcome).isEqualTo(StoreOutcome.STORED);
    assertThat(published()).isEqualTo(new Published("a", 2, secondRun));
    assertThat(reader.byId(chosen.id())).contains(repriced);
    assertThat(storedPrice("itinerary_a", chosen)).isEqualByComparingTo("126.00");
    assertThat(
            jdbc.sql("SELECT quoted_at FROM itinerary_a WHERE id = ?")
                .param(chosen.id().value())
                .query(OffsetDateTime.class)
                .single()
                .toInstant())
        .isEqualTo(quotedAt);
    assertThat(reader.byId(other.id())).contains(other);
    assertThat(storedPrice("itinerary_b", chosen)).isEqualByComparingTo("105.00");
  }

  /**
   * A run replaced the inventory after the choice was read, and the active generation no longer
   * holds it: nothing is written, in either slot.
   */
  @Test
  void answersGoneForAnItineraryTheActiveGenerationNoLongerHolds() {
    Itinerary gone = direct(1, "2026-11-25T07:15:00Z");
    publish(NOW, List.of(gone));
    publish(NOW.plusSeconds(1_800), List.of(direct(2, "2026-11-25T08:15:00Z")));

    StoreOutcome outcome =
        repository.updateInPlace(
            requoted(gone, gone.arrival(), "120.00", Instant.parse("2026-11-20T10:40:00Z")));

    assertThat(outcome).isEqualTo(StoreOutcome.GONE);
    assertThat(rows("itinerary_a")).isEqualTo(1);
    assertThat(storedPrice("itinerary_b", gone)).isEqualByComparingTo("105.00");
  }

  /** The ids of every itinerary, by duration, read one keyset page of one at a time. */
  private List<UUID> idsByDurationOnePerPage() {
    SearchCriteria everything =
        new SearchCriteria(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), DURATION);
    List<UUID> ids = new ArrayList<>();
    Optional<SeekPosition> after = Optional.empty();
    for (int pages = 0; pages < 10; pages++) {
      List<Itinerary> page = reader.page(everything, after, 1, NOW).itineraries();
      if (page.isEmpty()) {
        return ids;
      }
      ids.add(page.getFirst().id().value());
      after = Optional.of(SeekPosition.of(page.getFirst()));
    }
    throw new AssertionError("still paging after 10 pages: " + ids);
  }

  /**
   * N1: a re-quote that lands an hour later keeps the id, and every column derived from the legs
   * follows it, so a page by duration still seeks from where the row now sorts. With the old
   * duration left in its column, the row would sort first while its legs put the next page's seek
   * after the 2.5 h trip, which would be skipped.
   */
  @Test
  void keepsAPageByDurationWholeWhenARequoteMovesTheArrival() {
    Itinerary twoHours = direct(1, "2026-11-25T07:15:00Z");
    Itinerary twoAndAHalfHours = direct(2, "2026-11-25T08:15:00Z", Duration.ofMinutes(150));
    Itinerary threeAndAHalfHours = direct(3, "2026-11-25T09:15:00Z", Duration.ofMinutes(210));
    publish(NOW, List.of(twoHours, twoAndAHalfHours, threeAndAHalfHours));
    Itinerary threeHours =
        requoted(
            twoHours,
            twoHours.departure().plusHours(3),
            "100.00",
            Instant.parse("2026-11-20T10:40:00Z"));

    repository.updateInPlace(threeHours);

    assertThat(idsByDurationOnePerPage())
        .containsExactly(
            twoAndAHalfHours.id().value(),
            threeHours.id().value(),
            threeAndAHalfHours.id().value());
  }

  /**
   * A run holds the publication row for its whole load. An update meanwhile does not wait for the
   * row: it answers busy at once and writes nothing, and the run goes on to publish its own
   * download.
   */
  @Test
  void answersBusyAtOnceWhileARunHoldsThePublication() throws SQLException {
    Itinerary chosen = direct(1, "2026-11-25T07:15:00Z");
    publish(NOW, List.of(chosen));
    Itinerary repriced = requoted(chosen, "120.00");
    try (Connection run = dataSource.getConnection()) {
      run.setAutoCommit(false);
      try (Statement statement = run.createStatement()) {
        statement.execute("SELECT active_slot FROM inventory_publication FOR UPDATE");
      }

      StoreOutcome outcome =
          assertTimeoutPreemptively(
              Duration.ofSeconds(2), () -> repository.updateInPlace(repriced));

      run.rollback();
      assertThat(outcome).isEqualTo(StoreOutcome.BUSY);
    }
    assertThat(storedPrice("itinerary_b", chosen)).isEqualByComparingTo("105.00");
  }

  /**
   * Only a refused lock means a run holds the publication row: an update that cannot read the row
   * for any other reason fails instead of answering busy. Here the row is deleted inside the
   * enclosing transaction, which always rolls back, so the row is back for the next test even if
   * the update answered.
   */
  @Test
  void failsAnUpdateThatCannotReadThePublication() {
    Itinerary chosen = direct(1, "2026-11-25T07:15:00Z");
    publish(NOW, List.of(chosen));

    assertThatThrownBy(
            () ->
                enclosing.executeWithoutResult(
                    status -> {
                      status.setRollbackOnly();
                      jdbc.sql("DELETE FROM inventory_publication").update();
                      repository.updateInPlace(requoted(chosen, "120.00"));
                    }))
        .isInstanceOf(IncorrectResultSizeDataAccessException.class);

    assertThat(published()).isEqualTo(new Published("b", 1, NOW));
    assertThat(storedPrice("itinerary_b", chosen)).isEqualByComparingTo("105.00");
  }

  /**
   * An update's transaction is bounded as the load's is: a statement that hangs is cut after 5
   * minutes, and past the publication row, which it never waits for, a lock is awaited for 5 s at
   * most (a row another re-price of the same itinerary is rewriting, say). Each setting is read in
   * the enclosing transaction once the update has written, and is gone after the commit.
   */
  @ParameterizedTest
  @CsvSource({"statement_timeout, 5min", "lock_timeout, 5s"})
  void boundsTheTransactionOfAnUpdate(String setting, String bound) {
    Itinerary chosen = direct(1, "2026-11-25T07:15:00Z");
    publish(NOW, List.of(chosen));

    String duringTheUpdate =
        enclosing.execute(
            status -> {
              repository.updateInPlace(requoted(chosen, "120.00"));
              return jdbc.sql("SHOW " + setting).query(String.class).single();
            });

    assertThat(duringTheUpdate).isEqualTo(bound);
    assertThat(jdbc.sql("SHOW " + setting).query(String.class).single()).isEqualTo("0");
  }

  /**
   * Waits until a statement of another backend waits for a lock the backend {@code holder} holds.
   */
  private void awaitAStatementBlockedBy(int holder) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")
            .param(holder)
            .query(Long.class)
            .single()
        == 0) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("nothing waited for backend " + holder);
      }
      Thread.sleep(20);
    }
  }

  /**
   * An update run in the enclosing transaction, on a thread of its own, and held open after its
   * write, with its share of the publication row, until {@link #release()}.
   */
  private final class HeldUpdate {

    private final CompletableFuture<Integer> backend = new CompletableFuture<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final CompletableFuture<StoreOutcome> outcome;

    HeldUpdate(Itinerary repriced) {
      outcome =
          CompletableFuture.supplyAsync(
              () ->
                  enclosing.execute(
                      status -> {
                        StoreOutcome stored = repository.updateInPlace(repriced);
                        backend.complete(
                            jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        try {
                          released.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return stored;
                      }));
    }

    /** The update's backend, once the update has written. */
    int backendOnceWritten() throws Exception {
      return backend.get(10, TimeUnit.SECONDS);
    }

    /** Lets the update commit. */
    void release() {
      released.countDown();
    }

    /** What the update answered, once it has committed. */
    StoreOutcome outcome() throws Exception {
      return outcome.get(10, TimeUnit.SECONDS);
    }
  }

  /**
   * An update in progress keeps the slot it read active until it commits: a run that starts
   * meanwhile waits for the update's share of the publication row before it touches either slot,
   * then loads the other slot and switches to it.
   */
  @Test
  void keepsTheSlotAnUpdateReadActiveUntilItCommits() throws Exception {
    Itinerary chosen = direct(1, "2026-11-25T07:15:00Z");
    publish(NOW, List.of(chosen));
    HeldUpdate update = new HeldUpdate(requoted(chosen, "120.00"));
    CompletableFuture<Publication> run;
    try {
      int backend = update.backendOnceWritten();
      run =
          CompletableFuture.supplyAsync(
              () ->
                  repository.replaceInventory(
                      NOW.plusSeconds(1_800),
                      sink -> sink.accept(direct(2, "2026-11-25T08:15:00Z"))));

      awaitAStatementBlockedBy(backend);

      assertThat(published().activeSlot()).isEqualTo("b");
    } finally {
      update.release();
    }
    assertThat(update.outcome()).isEqualTo(StoreOutcome.STORED);
    assertThat(run.get(10, TimeUnit.SECONDS).generation()).isEqualTo(2);
    assertThat(storedPrice("itinerary_b", chosen)).isEqualByComparingTo("126.00");
    assertThat(published().activeSlot()).isEqualTo("a");
  }

  /**
   * Two re-prices at once both store: the first is held open after its write, so it keeps its share
   * of the publication row, and the second stores meanwhile, at once.
   */
  @Test
  void storesTwoRepricesAtOnce() throws Exception {
    Itinerary first = direct(1, "2026-11-25T07:15:00Z");
    Itinerary second = direct(2, "2026-11-25T08:15:00Z");
    publish(NOW, List.of(first, second));
    HeldUpdate firstUpdate = new HeldUpdate(requoted(first, "120.00"));
    try {
      firstUpdate.backendOnceWritten();

      StoreOutcome secondOutcome =
          assertTimeoutPreemptively(
              Duration.ofSeconds(2), () -> repository.updateInPlace(requoted(second, "130.00")));

      assertThat(secondOutcome).isEqualTo(StoreOutcome.STORED);
    } finally {
      firstUpdate.release();
    }
    assertThat(firstUpdate.outcome()).isEqualTo(StoreOutcome.STORED);
    assertThat(storedPrice("itinerary_b", first)).isEqualByComparingTo("126.00");
    assertThat(storedPrice("itinerary_b", second)).isEqualByComparingTo("136.50");
  }

  /**
   * Two re-prices of the same itinerary at once: the first is held open after its write, and the
   * second waits for the row the first rewrote. Once the first commits, the row the second's delete
   * waited for is gone and the first's copy is not in that statement's snapshot, so the second
   * answers gone and writes nothing: the store keeps the first's quote.
   */
  @Test
  void keepsTheFirstOfTwoRepricesOfOneItinerary() throws Exception {
    Itinerary chosen = direct(1, "2026-11-25T07:15:00Z");
    publish(NOW, List.of(chosen));
    HeldUpdate firstUpdate = new HeldUpdate(requoted(chosen, "120.00"));
    CompletableFuture<StoreOutcome> secondUpdate;
    try {
      int first = firstUpdate.backendOnceWritten();
      secondUpdate =
          CompletableFuture.supplyAsync(() -> repository.updateInPlace(requoted(chosen, "130.00")));

      awaitAStatementBlockedBy(first);
    } finally {
      firstUpdate.release();
    }

    assertThat(firstUpdate.outcome()).isEqualTo(StoreOutcome.STORED);
    assertThat(secondUpdate.get(10, TimeUnit.SECONDS)).isEqualTo(StoreOutcome.GONE);
    assertThat(storedPrice("itinerary_b", chosen)).isEqualByComparingTo("126.00");
  }
}
