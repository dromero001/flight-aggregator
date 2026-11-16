package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.published;
import static com.flightaggregator.adapter.persistence.support.LockWaits.awaitAStatementBlockedBy;
import static com.flightaggregator.domain.Provider.ALPHA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.adapter.persistence.support.ItinerarySlots;
import com.flightaggregator.adapter.persistence.support.ItinerarySlots.Published;
import com.flightaggregator.adapter.persistence.support.OneSession;
import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * A stale-route refresh's rewrite of the itineraries through one origin in the active slot (Q2-B),
 * and how it shares the publication row with a run's load ({@link
 * JdbcItineraryRepositoryLoadTest}).
 */
class JdbcItineraryRepositoryRefreshTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  private static final Airport MADRID = new Airport("MAD");

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final DataSourceTransactionManager transactions =
      new DataSourceTransactionManager(dataSource);
  private final JdbcItineraryRepository repository =
      new JdbcItineraryRepository(transactions, new JsonFactory(), 37);

  /**
   * A transaction of the repository's manager. A refresh inside it joins it, so the test can read
   * the refresh's settings, or keep its locks, before the commit.
   */
  private final TransactionTemplate enclosing = new TransactionTemplate(transactions);

  /** Madrid-London, Barcelona-Madrid, Barcelona-Madrid-London and Barcelona-London. */
  private final Leg madLhr = leg(1, "MAD", "LHR", "2026-11-25T07:15:00Z", "100.00");

  private final Leg bcnMad = leg(2, "BCN", "MAD", "2026-11-25T04:00:00Z", "100.00");
  private final Itinerary madridLondon = trip(madLhr);
  private final Itinerary barcelonaMadrid = trip(bcnMad);
  private final Itinerary barcelonaMadridLondon = trip(bcnMad, madLhr);
  private final Itinerary barcelonaLondon =
      trip(leg(3, "BCN", "LHR", "2026-11-25T08:00:00Z", "100.00"));

  @BeforeEach
  void startFromAFreshDatabase() {
    ItinerarySlots.empty(jdbc);
  }

  /** {@code IB<number>}, two hours long, quoted on 2026-11-15. */
  private static Leg leg(int number, String from, String to, String departure, String price) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Leg(
        new Carrier("IB"),
        number,
        new Airport(from),
        new Airport(to),
        departsAt,
        departsAt.plusHours(2),
        Money.euros(price),
        ALPHA,
        Instant.parse("2026-11-15T10:00:00Z"));
  }

  private static Itinerary trip(Leg... legs) {
    return new Itinerary(List.of(legs));
  }

  /** The four trips, published by a run at {@link #NOW} into slot b, as generation 1. */
  private void publishTheFourTrips() {
    repository.replaceInventory(
        NOW,
        sink ->
            List.of(madridLondon, barcelonaMadrid, barcelonaMadridLondon, barcelonaLondon)
                .forEach(sink));
  }

  private Publication refreshMadrid(Itinerary... itineraries) {
    return repository.replaceThrough(MADRID, sink -> List.of(itineraries).forEach(sink));
  }

  private List<UUID> ids(String slot) {
    return jdbc.sql("SELECT id FROM " + slot).query(UUID.class).list();
  }

  private BigDecimal storedPrice(String slot, Itinerary itinerary) {
    return jdbc.sql("SELECT price FROM " + slot + " WHERE id = ?")
        .param(itinerary.id().value())
        .query(BigDecimal.class)
        .single();
  }

  /**
   * The rows with a leg departing from Madrid are replaced by the writer's, the others stay, and
   * the generation moves on while the time of the run's publication stays. The rewrite counts what
   * it wrote and the active slot's DEFAULT partition, whose day partitions it leaves as they were.
   */
  @Test
  void replacesOnlyTheItinerariesThroughTheOrigin() {
    publishTheFourTrips();
    Itinerary madridLondonDearer = trip(leg(1, "MAD", "LHR", "2026-11-25T07:15:00Z", "120.00"));
    Itinerary beyondThePartitions = trip(leg(4, "MAD", "LHR", "2026-12-30T07:15:00Z", "100.00"));

    Publication publication = refreshMadrid(madridLondonDearer, beyondThePartitions);

    assertThat(publication)
        .isEqualTo(new Publication(2, 2, 1, Instant.parse("2026-12-28T00:00:00Z")));
    assertThat(published(jdbc)).isEqualTo(new Published("b", 2, NOW));
    assertThat(ids("itinerary_b"))
        .containsExactlyInAnyOrder(
            barcelonaMadrid.id().value(),
            barcelonaLondon.id().value(),
            madridLondonDearer.id().value(),
            beyondThePartitions.id().value());
    assertThat(storedPrice("itinerary_b", madridLondon)).isEqualByComparingTo("126.00");
  }

  /**
   * A writer that fails after its first itinerary leaves the active slot as the run published it:
   * the delete rolls back with the copy, and the generation does not move. The writer's exception
   * propagates as it was thrown.
   */
  @Test
  void aFailedRefreshLeavesTheActiveSlotWhole() {
    publishTheFourTrips();
    RuntimeException failure = new IllegalStateException("the build failed");

    assertThatThrownBy(
            () ->
                repository.replaceThrough(
                    MADRID,
                    sink -> {
                      sink.accept(trip(leg(1, "MAD", "LHR", "2026-11-25T07:15:00Z", "120.00")));
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(published(jdbc)).isEqualTo(new Published("b", 1, NOW));
    assertThat(ids("itinerary_b"))
        .containsExactlyInAnyOrder(
            madridLondon.id().value(),
            barcelonaMadrid.id().value(),
            barcelonaMadridLondon.id().value(),
            barcelonaLondon.id().value());
    assertThat(storedPrice("itinerary_b", madridLondon)).isEqualByComparingTo("105.00");
  }

  /**
   * "No run has published yet" is read from the publication row, not from the slot's partitions: a
   * day partition made outside the repository does not let a refresh publish a generation no run
   * made.
   */
  @Test
  void refusesARefreshBeforeTheFirstPublicationWhateverPartitionsItsSlotHolds() {
    jdbc.sql(
            "CREATE TABLE itinerary_a_20261125 PARTITION OF itinerary_a"
                + " FOR VALUES FROM ('2026-11-25T00:00:00Z') TO ('2026-11-26T00:00:00Z')")
        .update();

    assertThatThrownBy(() -> refreshMadrid(madridLondon)).isInstanceOf(IllegalStateException.class);

    assertThat(published(jdbc)).isEqualTo(new Published("a", 0, null));
    assertThat(ids("itinerary_a")).isEmpty();
  }

  /**
   * Before the first run, the active slot has no itinerary to rewrite: the refresh fails, and the
   * empty inventory stays as it was. Search asks for no refresh then; this keeps the repository
   * from publishing a generation no run made.
   */
  @Test
  void refusesARefreshBeforeTheFirstPublication() {
    assertThatThrownBy(() -> refreshMadrid(madridLondon))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No run has published the inventory yet: nothing to refresh");

    assertThat(published(jdbc)).isEqualTo(new Published("a", 0, null));
    assertThat(ids("itinerary_a")).isEmpty();
  }

  /**
   * A refresh's transaction is bounded as every store's is: a statement that hangs is cut after 5
   * minutes, and a lock, the publication row's included, is awaited for 5 s at most (a run's load
   * holds that row for minutes). Each setting is read in an enclosing transaction once the refresh
   * has written, then after the commit in the same session, where it must be gone.
   */
  @ParameterizedTest
  @CsvSource({"statement_timeout, 5min", "lock_timeout, 5s"})
  void boundsTheTransactionOfARefresh(String setting, String bound) {
    publishTheFourTrips();
    try (OneSession session = new OneSession()) {
      DataSourceTransactionManager sessionTransactions =
          new DataSourceTransactionManager(session.dataSource());
      JdbcItineraryRepository sessionRepository =
          new JdbcItineraryRepository(sessionTransactions, new JsonFactory(), 37);

      String duringTheRefresh =
          new TransactionTemplate(sessionTransactions)
              .execute(
                  status -> {
                    sessionRepository.replaceThrough(MADRID, sink -> sink.accept(madridLondon));
                    return session.show(setting);
                  });

      assertThat(duringTheRefresh).isEqualTo(bound);
      assertThat(session.show(setting)).isEqualTo("0");
    }
  }

  /**
   * A refresh that starts while a run holds the publication row waits for the run before it reads
   * the active slot, then writes into the slot the run switched to, not the one it switched from.
   * The run is a second connection that holds the row, then switches and commits.
   */
  @Test
  void writesIntoTheSlotARunSwitchedToWhileItWaited() throws Exception {
    publishTheFourTrips();
    repository.replaceInventory(
        NOW.plusSeconds(1_800),
        sink ->
            List.of(madridLondon, barcelonaMadrid, barcelonaMadridLondon, barcelonaLondon)
                .forEach(sink));
    CompletableFuture<Publication> refresh;
    try (Connection run = dataSource.getConnection()) {
      run.setAutoCommit(false);
      int runBackend;
      try (Statement statement = run.createStatement()) {
        statement.execute("SELECT active_slot FROM inventory_publication FOR UPDATE");
        try (ResultSet backend = statement.executeQuery("SELECT pg_backend_pid()")) {
          backend.next();
          runBackend = backend.getInt(1);
        }
      }
      refresh =
          CompletableFuture.supplyAsync(
              () -> refreshMadrid(trip(leg(1, "MAD", "LHR", "2026-11-25T07:15:00Z", "120.00"))));

      awaitAStatementBlockedBy(jdbc, runBackend);

      try (Statement statement = run.createStatement()) {
        statement.execute(
            "UPDATE inventory_publication SET active_slot = 'b', generation = generation + 1");
      }
      run.commit();
    }
    assertThat(refresh.get(10, TimeUnit.SECONDS).generation()).isEqualTo(4);
    assertThat(storedPrice("itinerary_b", madridLondon)).isEqualByComparingTo("126.00");
    assertThat(storedPrice("itinerary_a", madridLondon)).isEqualByComparingTo("105.00");
  }

  /**
   * A refresh of Madrid run in a transaction of the repository's manager, on a thread of its own,
   * and held open after its write, with the publication row, until {@link #release()}.
   */
  private final class HeldRefresh {

    private final CompletableFuture<Integer> backend = new CompletableFuture<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final CompletableFuture<Publication> publication;

    HeldRefresh(Itinerary... itineraries) {
      publication =
          CompletableFuture.supplyAsync(
              () ->
                  enclosing.execute(
                      status -> {
                        Publication written = refreshMadrid(itineraries);
                        backend.complete(
                            jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        try {
                          released.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return written;
                      }));
    }

    /** The refresh's backend, once the refresh has written. */
    int backendOnceWritten() throws Exception {
      return backend.get(10, TimeUnit.SECONDS);
    }

    /** Lets the refresh commit. */
    void release() {
      released.countDown();
    }

    /** What the refresh published, once it has committed. */
    Publication publication() throws Exception {
      return publication.get(10, TimeUnit.SECONDS);
    }
  }

  /**
   * Q9: a run that starts while a refresh writes waits for the publication row longer than the 5 s
   * its other locks wait, since a refresh's delete and {@code COPY} take seconds. Once the refresh
   * commits, the run loads the other slot and switches to it, one generation later; the slot the
   * refresh wrote stays whole for a search still reading it.
   */
  @Test
  void aRunSwitchesAfterARefreshReleasesThePublicationRow() throws Exception {
    publishTheFourTrips();
    HeldRefresh refresh =
        new HeldRefresh(trip(leg(1, "MAD", "LHR", "2026-11-25T07:15:00Z", "120.00")));
    Instant secondRun = NOW.plusSeconds(1_800);
    CompletableFuture<Publication> run;
    try {
      int backend = refresh.backendOnceWritten();
      run =
          CompletableFuture.supplyAsync(
              () -> repository.replaceInventory(secondRun, sink -> sink.accept(barcelonaLondon)));

      awaitAStatementBlockedBy(jdbc, backend);
      Thread.sleep(Duration.ofMillis(5_500));

      assertThat(run).isNotDone();
    } finally {
      refresh.release();
    }
    assertThat(refresh.publication().generation()).isEqualTo(2);
    assertThat(run.get(10, TimeUnit.SECONDS).generation()).isEqualTo(3);
    assertThat(published(jdbc)).isEqualTo(new Published("a", 3, secondRun));
    assertThat(ids("itinerary_a")).containsExactly(barcelonaLondon.id().value());
    assertThat(storedPrice("itinerary_b", madridLondon)).isEqualByComparingTo("126.00");
  }
}
