package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.published;
import static com.flightaggregator.adapter.persistence.support.ItinerarySlots.rows;
import static com.flightaggregator.adapter.persistence.support.LockWaits.awaitAStatementBlockedBy;
import static com.flightaggregator.adapter.persistence.support.Trips.direct;
import static com.flightaggregator.application.port.in.SortOrder.DURATION;
import static com.flightaggregator.domain.Provider.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.flightaggregator.adapter.persistence.support.ItinerarySlots;
import com.flightaggregator.adapter.persistence.support.ItinerarySlots.Published;
import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * A re-price's update in place of one row of the active slot (Q5), and how it shares the
 * publication row with a run's load ({@link JdbcItineraryRepositoryLoadTest}).
 */
class JdbcItineraryRepositoryUpdateTest {

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

  private Publication publish(Instant now, List<Itinerary> itineraries) {
    return repository.replaceInventory(now, sink -> itineraries.forEach(sink));
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
    assertThat(published(jdbc)).isEqualTo(new Published("a", 2, secondRun));
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
    assertThat(rows(jdbc, "itinerary_a")).isEqualTo(1);
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

    assertThat(published(jdbc)).isEqualTo(new Published("b", 1, NOW));
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

      awaitAStatementBlockedBy(jdbc, backend);

      assertThat(published(jdbc).activeSlot()).isEqualTo("b");
    } finally {
      update.release();
    }
    assertThat(update.outcome()).isEqualTo(StoreOutcome.STORED);
    assertThat(run.get(10, TimeUnit.SECONDS).generation()).isEqualTo(2);
    assertThat(storedPrice("itinerary_b", chosen)).isEqualByComparingTo("126.00");
    assertThat(published(jdbc).activeSlot()).isEqualTo("a");
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
}
