package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.domain.Provider.ALPHA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.out.Publication;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.json.JsonFactory;

class JdbcItineraryRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcClient jdbc = JdbcClient.create(dataSource);
  private final JdbcItineraryRepository repository =
      new JdbcItineraryRepository(
          dataSource,
          new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
          new JsonFactory(),
          37);

  @BeforeEach
  void startFromAFreshDatabase() {
    jdbc.sql("TRUNCATE itinerary_a, itinerary_b").update();
    for (String slot : List.of("itinerary_a", "itinerary_b")) {
      partitions(slot).stream()
          .filter(partition -> !partition.endsWith("_default"))
          .forEach(partition -> jdbc.sql("DROP TABLE " + partition).update());
    }
    jdbc.sql(
            "UPDATE inventory_publication SET active_slot = 'a', generation = 0, published_at = NULL")
        .update();
  }

  /** {@code IB<number>} from Madrid to London, departing at {@code departure} for two hours. */
  private static Itinerary direct(int number, String departure) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Itinerary(
        List.of(
            new Leg(
                new Carrier("IB"),
                number,
                new Airport("MAD"),
                new Airport("LHR"),
                departsAt,
                departsAt.plusHours(2),
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

  /** The partitions of a slot, by name, its DEFAULT partition included. */
  private List<String> partitions(String slot) {
    return jdbc.sql(
            "SELECT relid::text FROM pg_partition_tree(CAST(? AS regclass)) WHERE isleaf"
                + " ORDER BY 1")
        .param(slot)
        .query(String.class)
        .list();
  }

  @Test
  void publishesIntoTheInactiveSlotAndSwitches() {
    Publication publication =
        publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-11-25T08:15:00Z")));

    assertThat(publication).isEqualTo(new Publication(1, 2, 0, LocalDate.parse("2026-12-27")));
    assertThat(published()).isEqualTo(new Published("b", 1, NOW));
    assertThat(rows("itinerary_b")).isEqualTo(2);
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
    assertThat(partitions("itinerary_b"))
        .hasSize(39)
        .doesNotHaveDuplicates()
        .startsWith("itinerary_b_20261120")
        .endsWith("itinerary_b_20261227", "itinerary_b_default");
    // The DDL touched only the slot it loaded.
    assertThat(partitions("itinerary_a")).startsWith("itinerary_a_20261119");
  }

  @Test
  void putsADepartureBeyondThePartitionsInTheDefaultPartition() {
    Publication publication =
        publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"), direct(2, "2026-12-30T07:15:00Z")));

    assertThat(publication).isEqualTo(new Publication(1, 2, 1, LocalDate.parse("2026-12-27")));
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

  @Test
  void failsOnAPartitionOfTheSlotItDidNotName() {
    jdbc.sql(
            "CREATE TABLE itinerary_b_archive PARTITION OF itinerary_b"
                + " FOR VALUES FROM ('2026-01-01T00:00:00Z') TO ('2026-02-01T00:00:00Z')")
        .update();

    assertThatThrownBy(() -> publish(NOW, List.of(direct(1, "2026-11-25T07:15:00Z"))))
        .isInstanceOf(DateTimeParseException.class)
        .hasMessageContaining("archive");

    assertThat(published()).isEqualTo(new Published("a", 0, null));
    assertThat(partitions("itinerary_b")).contains("itinerary_b_archive");
  }
}
