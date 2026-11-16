package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.flightaggregator.adapter.persistence.support.ItinerarySlots;
import com.flightaggregator.adapter.persistence.support.OneSession;
import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.in.InventoryUnavailableException;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.StoredPage;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.sql.Connection;
import java.sql.ResultSet;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

class JdbcItineraryReaderTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  private static final SearchCriteria EVERYTHING_BY_PRICE = everything(SortOrder.PRICE);

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final JdbcItineraryReader reader =
      new JdbcItineraryReader(new DataSourceTransactionManager(dataSource), new JsonFactory());

  private final JdbcItineraryRepository repository =
      new JdbcItineraryRepository(
          new DataSourceTransactionManager(dataSource), new JsonFactory(), 37);

  @BeforeEach
  void startFromAFreshDatabase() {
    ItinerarySlots.empty(jdbc);
  }

  /** A leg priced {@code price} before the markup, quoted before {@link #NOW}. */
  private static Leg leg(
      String carrier,
      int number,
      String from,
      String to,
      String departure,
      int minutes,
      String price) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Leg(
        new Carrier(carrier),
        number,
        new Airport(from),
        new Airport(to),
        departsAt,
        departsAt.plusMinutes(minutes),
        Money.euros(price),
        Provider.ALPHA,
        Instant.parse("2026-11-20T09:00:00Z"));
  }

  private static Itinerary trip(Leg... legs) {
    return new Itinerary(List.of(legs));
  }

  private void publish(Itinerary... itineraries) {
    repository.replaceInventory(NOW, sink -> List.of(itineraries).forEach(sink));
  }

  @Test
  void returnsNothingBeforeTheFirstPublication() {
    assertThat(reader.state()).isEqualTo(new InventoryState(0, Optional.empty()));
    assertThat(reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 10, NOW))
        .isEqualTo(new StoredPage(0, List.of()));
  }

  @Test
  void readsAPageInPriceOrder() {
    // Final prices 315.00, 105.00 and 210.00; the cheapest departs last.
    Itinerary dearest = trip(leg("IB", 1, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "300"));
    Itinerary cheapest = trip(leg("IB", 2, "MAD", "LHR", "2026-11-23T07:00:00+01:00", 120, "100"));
    Itinerary middle = trip(leg("IB", 3, "MAD", "LHR", "2026-11-22T07:00:00+01:00", 120, "200"));
    publish(dearest, cheapest, middle);

    assertThat(reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 2, NOW))
        .isEqualTo(new StoredPage(1, List.of(cheapest, middle)));
  }

  /**
   * The ids of every itinerary that matches, read page by page, each page after the last row of the
   * one before, until a page is short. A seek that never advances fails instead of looping.
   */
  private List<UUID> idsPageByPage(SearchCriteria criteria, int pageSize) {
    List<UUID> ids = new ArrayList<>();
    Optional<SeekPosition> after = Optional.empty();
    for (int pages = 0; pages < 20; pages++) {
      List<Itinerary> page = reader.page(criteria, after, pageSize, NOW).itineraries();
      page.forEach(itinerary -> ids.add(itinerary.id().value()));
      if (page.size() < pageSize) {
        return ids;
      }
      after = Optional.of(SeekPosition.of(page.getLast()));
    }
    throw new AssertionError("still paging after 20 pages: " + ids.size() + " ids");
  }

  /** The oracle (R11): the ids of the first publication's slot in PostgreSQL's own order. */
  private List<UUID> idsInSqlOrder(String key) {
    return jdbc.sql("SELECT id FROM itinerary_b ORDER BY " + key + ", id").query(UUID.class).list();
  }

  @Test
  void pagesThroughTiesWithoutRepeatsOrGaps() {
    OffsetDateTime first = OffsetDateTime.parse("2026-11-21T07:00:00+01:00");
    publish(
        IntStream.rangeClosed(1, 25)
            .mapToObj(
                number ->
                    trip(
                        leg(
                            "IB",
                            number,
                            "MAD",
                            "LHR",
                            first.plusMinutes(number).toString(),
                            120,
                            "100")))
            .toArray(Itinerary[]::new));

    List<UUID> ids = idsPageByPage(EVERYTHING_BY_PRICE, 10);

    assertThat(ids).hasSize(25).doesNotHaveDuplicates().isEqualTo(idsInSqlOrder("price"));
  }

  private static SearchCriteria everything(SortOrder sort) {
    return new SearchCriteria(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), sort);
  }

  private static List<UUID> ids(Itinerary... itineraries) {
    return Stream.of(itineraries).map(itinerary -> itinerary.id().value()).toList();
  }

  /** In pages of 2, so the seek of each key runs too. */
  @Test
  void ordersByDepartureAndByDuration() {
    // In price order; by departure B, D, C, A; by duration C, B, A, D.
    Itinerary a = trip(leg("IB", 1, "MAD", "LHR", "2026-11-24T08:00:00+01:00", 300, "100"));
    Itinerary b = trip(leg("IB", 2, "MAD", "LHR", "2026-11-21T08:00:00+01:00", 200, "200"));
    Itinerary c = trip(leg("IB", 3, "MAD", "LHR", "2026-11-23T08:00:00+01:00", 100, "300"));
    Itinerary d = trip(leg("IB", 4, "MAD", "LHR", "2026-11-22T08:00:00+01:00", 400, "400"));
    publish(a, b, c, d);

    assertThat(idsPageByPage(everything(SortOrder.DEPARTURE), 2)).isEqualTo(ids(b, d, c, a));
    assertThat(idsPageByPage(everything(SortOrder.DURATION), 2)).isEqualTo(ids(c, b, a, d));
  }

  /** By price, each filter given or {@code null} for absent. */
  private static SearchCriteria filters(
      String origin, String destination, String maxPrice, String carrier) {
    return new SearchCriteria(
        Optional.ofNullable(origin).map(Airport::new),
        Optional.ofNullable(destination).map(Airport::new),
        Optional.ofNullable(maxPrice).map(Money::euros),
        Optional.ofNullable(carrier).map(Carrier::new),
        SortOrder.PRICE);
  }

  /** The ids of one page large enough for every match, in any order: prices tie. */
  private List<UUID> idsMatching(SearchCriteria criteria) {
    return reader.page(criteria, Optional.empty(), 100, NOW).itineraries().stream()
        .map(itinerary -> itinerary.id().value())
        .toList();
  }

  /** Each trip but the first fails exactly one of the four filters. */
  @Test
  void appliesEachFilterAndTheirCombination() {
    // 105.00, equal to the Max Price below.
    Itinerary matchesAll =
        trip(leg("IB", 1, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "100"));
    Itinerary tooDear = trip(leg("IB", 2, "MAD", "LHR", "2026-11-21T08:00:00+01:00", 120, "200"));
    Itinerary fromBarcelona =
        trip(leg("IB", 3, "BCN", "LHR", "2026-11-21T07:00:00+01:00", 120, "100"));
    Itinerary toParis = trip(leg("IB", 4, "MAD", "CDG", "2026-11-21T07:00:00+01:00", 120, "100"));
    // MAD to LHR at 94.50, interlining an IB leg with an AF one.
    Itinerary interline =
        trip(
            leg("IB", 5, "MAD", "CDG", "2026-11-21T07:00:00+01:00", 120, "40"),
            leg("AF", 6, "CDG", "LHR", "2026-11-21T10:00:00+01:00", 90, "50"));
    publish(matchesAll, tooDear, fromBarcelona, toParis, interline);

    assertThat(idsMatching(filters("MAD", null, null, null)))
        .containsExactlyInAnyOrderElementsOf(ids(matchesAll, tooDear, toParis, interline));
    assertThat(idsMatching(filters(null, "LHR", null, null)))
        .containsExactlyInAnyOrderElementsOf(ids(matchesAll, tooDear, fromBarcelona, interline));
    assertThat(idsMatching(filters(null, null, "105.00", null)))
        .containsExactlyInAnyOrderElementsOf(ids(matchesAll, fromBarcelona, toParis, interline));
    assertThat(idsMatching(filters(null, null, null, "IB")))
        .containsExactlyInAnyOrderElementsOf(ids(matchesAll, tooDear, fromBarcelona, toParis));
    assertThat(idsMatching(filters("MAD", "LHR", "105.00", "IB")))
        .containsExactlyElementsOf(ids(matchesAll));
  }

  /**
   * Plan 5's end-of-plan review (I1): an origin without a destination, sorted by price, is read
   * through an index that starts with the origin, so a long-haul origin, whose trips are the
   * dearest, is not found by walking every cheaper trip first. At this volume a sequential scan
   * would be cheaper, so the session turns it off to ask which index the planner picks for this
   * statement.
   */
  @Test
  void readsAnOriginByPriceThroughAnIndexStartingWithTheOrigin() throws SQLException {
    List<Itinerary> trips = new ArrayList<>();
    IntStream.rangeClosed(1, 40)
        .forEach(
            n -> {
              String departure = "2026-11-21T%02d:%02d:00+01:00".formatted(6 + n / 4, n % 4 * 15);
              trips.add(trip(leg("IB", n, "MAD", "LHR", departure, 120, String.valueOf(50 + n))));
              trips.add(trip(leg("JL", n, "NRT", "LHR", departure, 720, String.valueOf(900 + n))));
            });
    publish(trips.toArray(Itinerary[]::new));
    KeysetQuery query =
        KeysetQuery.of(Slot.B, filters("NRT", null, null, null), Optional.empty(), 21, NOW);

    assertThat(indexKeysScannedBy(query)).containsExactly("origin, price, id");
  }

  /**
   * The key columns of the indexes the planner scans for a query, e.g. {@code price, id}, with
   * sequential scans turned off in its session.
   */
  private List<String> indexKeysScannedBy(KeysetQuery query) throws SQLException {
    List<String> plan = new ArrayList<>();
    try (Connection session = dataSource.getConnection();
        Statement settings = session.createStatement()) {
      settings.execute("SET enable_seqscan = off");
      try (var explain = session.prepareStatement("EXPLAIN " + query.sql())) {
        for (int i = 0; i < query.parameters().size(); i++) {
          explain.setObject(i + 1, query.parameters().get(i));
        }
        try (ResultSet lines = explain.executeQuery()) {
          while (lines.next()) {
            plan.add(lines.getString(1));
          }
        }
      }
    }
    return plan.stream()
        .flatMap(line -> Stream.of(line.split("Index Scan using ")).skip(1))
        .map(scan -> scan.substring(0, scan.indexOf(' ')))
        .map(
            index ->
                jdbc.sql(
                        "SELECT substring(indexdef FROM 'USING btree \\((.*)\\)')"
                            + " FROM pg_indexes WHERE indexname = ?")
                    .param(index)
                    .query(String.class)
                    .single())
        .distinct()
        .toList();
  }

  /** Search shows only departures after now (§4.1); at +01:00, NOW is 11:00 local. */
  @Test
  void hidesDepartedItineraries() {
    Itinerary departed = trip(leg("IB", 1, "MAD", "LHR", "2026-11-20T10:00:00+01:00", 120, "100"));
    Itinerary departingNow =
        trip(leg("IB", 2, "MAD", "LHR", "2026-11-20T11:00:00+01:00", 120, "200"));
    Itinerary later = trip(leg("IB", 3, "MAD", "LHR", "2026-11-20T12:00:00+01:00", 120, "300"));
    publish(departed, departingNow, later);

    assertThat(reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 10, NOW).itineraries())
        .containsExactly(later);
  }

  /** The second run publishes slot a; slot b still holds the first generation, whole. */
  @Test
  void readsTheActiveSlotWithItsGeneration() {
    Instant secondRun = NOW.plusSeconds(1_800);
    publish(trip(leg("IB", 1, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "100")));
    Itinerary current = trip(leg("IB", 2, "MAD", "LHR", "2026-11-21T08:00:00+01:00", 120, "200"));
    repository.replaceInventory(secondRun, sink -> sink.accept(current));

    assertThat(reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 10, NOW))
        .isEqualTo(new StoredPage(2, List.of(current)));
    assertThat(reader.state()).isEqualTo(new InventoryState(2, Optional.of(secondRun)));
  }

  /** Waits until a page's read of slot b waits for a lock, after it has read the generation. */
  private void awaitThePageWaitingForSlotB() throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (jdbc.sql(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND query LIKE 'SELECT legs FROM itinerary_b %'")
            .query(Long.class)
            .single()
        == 0) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("no page waited for slot b");
      }
      Thread.sleep(20);
    }
  }

  /**
   * A refresh (Q2-B) deletes rows of the active slot and bumps the generation in one transaction. A
   * page that read the generation before the refresh committed still reads that generation's rows,
   * so the page and its generation agree. The page's wait for the lock counts toward its 2 s bound,
   * a constant, so the refresh must commit within it; it does in milliseconds, but a stall of 2 s
   * on a loaded machine would cut the page and fail the test.
   */
  @Test
  void readsTheGenerationAndItsRowsInOneSnapshot() throws Exception {
    Itinerary first = trip(leg("IB", 1, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "100"));
    publish(first);
    try (Connection refresh = dataSource.getConnection()) {
      refresh.setAutoCommit(false);
      try (Statement statement = refresh.createStatement()) {
        statement.execute("LOCK TABLE itinerary_b IN ACCESS EXCLUSIVE MODE");
      }
      CompletableFuture<StoredPage> page =
          CompletableFuture.supplyAsync(
              () -> reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 10, NOW));
      awaitThePageWaitingForSlotB();

      try (Statement statement = refresh.createStatement()) {
        statement.execute("DELETE FROM itinerary_b");
        statement.execute("UPDATE inventory_publication SET generation = generation + 1");
      }
      refresh.commit();

      assertThat(page.get(15, TimeUnit.SECONDS)).isEqualTo(new StoredPage(1, List.of(first)));
    }
  }

  /**
   * How many client backends, this one and {@code holder} apart, run a statement or hold a
   * transaction open, aborted or not. An aborted transaction has no {@code xact_start}, so the
   * state is what tells.
   */
  private long busyBackendsBesides(Connection holder) throws SQLException {
    int holderPid;
    try (Statement statement = holder.createStatement();
        ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
      pid.next();
      holderPid = pid.getInt(1);
    }
    return jdbc.sql(
            "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'"
                + " AND state <> 'idle' AND pid NOT IN (pg_backend_pid(), ?)")
        .param(holderPid)
        .query(Long.class)
        .single();
  }

  /**
   * A read that takes longer than its bound, here waiting for a lock on its slot, fails with
   * PostgreSQL's statement timeout and rolls back: it holds neither its connection nor its {@code
   * ACCESS SHARE} lock on the slot any longer, which a run's {@code TRUNCATE} of the slot would
   * wait for. The failure is the port's {@link InventoryUnavailableException}, which the API
   * answers with 503 and Retry-After (plan 5's end-of-plan review, I1).
   */
  @Test
  void cutsAReadThatTakesLongerThanItsBound() throws SQLException {
    publish(trip(leg("IB", 1, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "100")));
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute("LOCK TABLE itinerary_b IN ACCESS EXCLUSIVE MODE");
      }

      Throwable failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(10),
              () ->
                  catchThrowable(
                      () -> reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 10, NOW)));

      // Spring translates PostgreSQL's query_canceled, 57014, into a QueryTimeoutException.
      assertThat(failure)
          .isInstanceOf(InventoryUnavailableException.class)
          .cause()
          .isInstanceOf(QueryTimeoutException.class);
      assertThat(((QueryTimeoutException) failure.getCause()).getMostSpecificCause())
          .isInstanceOfSatisfying(
              SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo("57014"))
          .hasMessageContaining("statement timeout");
      assertThat(busyBackendsBesides(holder)).isZero();
      holder.rollback();
    }
  }

  static Stream<Named<Consumer<JdbcItineraryReader>>> everyRead() {
    return Stream.of(
        Named.of("state", reader -> reader.state()),
        Named.of("page", reader -> reader.page(EVERYTHING_BY_PRICE, Optional.empty(), 10, NOW)),
        Named.of(
            "byId",
            reader ->
                reader.byId(
                    new ItineraryId(UUID.fromString("0f8e3a52-1c3b-4b6e-9d2a-5b7c8e9f0a1b")))));
  }

  /**
   * Each read bounds its statements at 2 s, in its own transaction alone. The read runs in an
   * enclosing transaction, which it joins, so the setting is read once the read has run; then,
   * after the commit, in the same session, where it must be gone, or it would bound whatever next
   * borrows a pooled connection.
   */
  @ParameterizedTest
  @MethodSource("everyRead")
  void boundsEachReadToTwoSeconds(Consumer<JdbcItineraryReader> read) {
    try (OneSession session = new OneSession()) {
      DataSourceTransactionManager transactions =
          new DataSourceTransactionManager(session.dataSource());
      JdbcItineraryReader sessionReader = new JdbcItineraryReader(transactions, new JsonFactory());

      String duringTheRead =
          new TransactionTemplate(transactions)
              .execute(
                  status -> {
                    read.accept(sessionReader);
                    return session.show("statement_timeout");
                  });

      assertThat(duringTheRead).isEqualTo("2s");
      assertThat(session.show("statement_timeout")).isEqualTo("0");
    }
  }

  /** Re-price must still find a choice that has left, to offer its alternative (Q4). */
  @Test
  void findsAnItineraryByIdEvenOnceDeparted() {
    Itinerary departed = trip(leg("IB", 1, "MAD", "LHR", "2026-11-20T10:00:00+01:00", 120, "100"));
    publish(departed, trip(leg("IB", 2, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "200")));

    assertThat(reader.byId(departed.id())).contains(departed);
  }

  /** Unknown to the active generation, even when the previous one, still whole, has it (Q4). */
  @Test
  void findsNothingForAnUnknownId() {
    Itinerary replaced = trip(leg("IB", 1, "MAD", "LHR", "2026-11-21T07:00:00+01:00", 120, "100"));
    publish(replaced);
    publish(trip(leg("IB", 2, "MAD", "LHR", "2026-11-21T08:00:00+01:00", 120, "200")));

    assertThat(
            reader.byId(new ItineraryId(UUID.fromString("0f8e3a52-1c3b-4b6e-9d2a-5b7c8e9f0a1b"))))
        .isEmpty();
    assertThat(reader.byId(replaced.id())).isEmpty();
  }
}
