package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.adapter.persistence.support.LockWaits;
import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.LatestRun;
import com.flightaggregator.application.port.out.ProviderRun;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcIngestionStatusStoreTest {

  private static final Instant FIRST_RUN_END = Instant.parse("2026-11-20T08:01:30.123456Z");
  private static final Instant SECOND_RUN_END = Instant.parse("2026-11-20T08:33:10.654321Z");

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcIngestionStatusStore store =
      new JdbcIngestionStatusStore(new DataSourceTransactionManager(dataSource));

  @BeforeEach
  void forgetEveryRun() {
    JdbcClient.create(dataSource)
        .sql("TRUNCATE ingestion_status, ingestion_status_discarded")
        .update();
  }

  /**
   * Q7-B: one row per provider, the latest run's, read back exactly, its discarded records by
   * reason included; an earlier run's discarded records do not linger.
   */
  @Test
  void keepsTheLatestRunPerProvider() {
    store.record(
        List.of(
            new ProviderRun(
                ALPHA,
                Optional.empty(),
                120,
                Map.of(DiscardReason.PRICE, 2, DiscardReason.SHAPE, 1),
                Optional.of(Instant.parse("2026-11-20T08:00:59.000001Z"))),
            new ProviderRun(BETA, Optional.empty(), 80, Map.of(), Optional.empty())),
        FIRST_RUN_END);
    List<ProviderRun> second =
        List.of(
            new ProviderRun(
                ALPHA,
                Optional.of(FailureReason.TIMEOUT),
                120,
                Map.of(),
                Optional.of(Instant.parse("2026-11-20T08:00:59.000001Z"))),
            new ProviderRun(
                BETA,
                Optional.empty(),
                81,
                Map.of(DiscardReason.UNKNOWN_AIRPORT, 4),
                Optional.of(Instant.parse("2026-11-20T08:32:01.5Z"))));

    store.record(second, SECOND_RUN_END);

    assertThat(store.latest()).contains(new LatestRun(SECOND_RUN_END, second));
  }

  /**
   * A run recorded while the latest is read does not mix into it: the read answers the run it began
   * with, its end, its providers and their discarded records, which the screen shows together.
   */
  @Test
  void readsTheLatestRunInOneSnapshot() throws Exception {
    List<ProviderRun> first =
        List.of(
            new ProviderRun(
                ALPHA, Optional.empty(), 120, Map.of(DiscardReason.PRICE, 2), Optional.empty()));
    store.record(first, FIRST_RUN_END);
    try (Connection run = dataSource.getConnection()) {
      run.setAutoCommit(false);
      try (Statement statement = run.createStatement()) {
        statement.execute("LOCK TABLE ingestion_status IN ACCESS EXCLUSIVE MODE");
      }
      CompletableFuture<Optional<LatestRun>> read = CompletableFuture.supplyAsync(store::latest);
      // The discarded records are read under no lock the run holds, so the read's first statement
      // to wait for the run is its read of the providers' rows.
      LockWaits.awaitAStatementBlockedBy(JdbcClient.create(dataSource), LockWaits.backendPid(run));

      try (Statement statement = run.createStatement()) {
        statement.execute("DELETE FROM ingestion_status");
        statement.execute(
            "INSERT INTO ingestion_status (provider, failure, offers_used, finished_at)"
                + " VALUES ('ALPHA', 'TIMEOUT', 0, '"
                + SECOND_RUN_END
                + "')");
      }
      run.commit();

      assertThat(read.get(15, TimeUnit.SECONDS)).contains(new LatestRun(FIRST_RUN_END, first));
    }
  }

  /** A run's providers are recorded together or not at all: a failure halfway leaves the last. */
  @Test
  void aFailedRecordLeavesTheLastRunWhole() {
    List<ProviderRun> first =
        List.of(
            new ProviderRun(ALPHA, Optional.empty(), 120, Map.of(), Optional.empty()),
            new ProviderRun(BETA, Optional.empty(), 80, Map.of(), Optional.empty()));
    store.record(first, FIRST_RUN_END);
    IllegalStateException halfway = new IllegalStateException("halfway");
    List<ProviderRun> failing =
        new AbstractList<>() {
          @Override
          public ProviderRun get(int index) {
            if (index == 1) {
              throw halfway;
            }
            return new ProviderRun(
                ALPHA, Optional.of(FailureReason.TIMEOUT), 120, Map.of(), Optional.empty());
          }

          @Override
          public int size() {
            return 2;
          }
        };

    assertThatThrownBy(() -> store.record(failing, SECOND_RUN_END)).isSameAs(halfway);

    assertThat(store.latest()).contains(new LatestRun(FIRST_RUN_END, first));
  }
}
