package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.LatestRun;
import com.flightaggregator.application.port.out.ProviderRun;
import com.flightaggregator.domain.Provider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps each provider's latest run in {@code ingestion_status}, and its discarded records by reason
 * in {@code ingestion_status_discarded} (V7). A run's providers are recorded in one transaction,
 * bounded by {@link StatementTimeout#STORE}, so a failure leaves the previous run's rows whole;
 * they are read back in one {@link BoundedSnapshot}, whose statements see one snapshot, so a read
 * never mixes two runs, and are bounded as a search's are, since the public health probe reads
 * them.
 */
public final class JdbcIngestionStatusStore implements IngestionStatusStore {

  private static final String INSERT_STATUS =
      "INSERT INTO ingestion_status (provider, failure, offers_used, newest_quoted_at, finished_at)"
          + " VALUES (?, ?, ?, ?, ?)";

  private static final String INSERT_DISCARDED =
      "INSERT INTO ingestion_status_discarded (provider, reason, records) VALUES (?, ?, ?)";

  private static final String SELECT_STATUSES =
      "SELECT provider, failure, offers_used, newest_quoted_at FROM ingestion_status";

  private static final String SELECT_DISCARDED =
      "SELECT provider, reason, records FROM ingestion_status_discarded";

  private final TransactionTemplate transaction;
  private final BoundedSnapshot snapshot;
  private final JdbcClient jdbc;

  /**
   * Its connections and its transactions come from the one manager, so its statements always run in
   * that manager's transaction (E6).
   */
  public JdbcIngestionStatusStore(DataSourceTransactionManager transactions) {
    this.transaction = new TransactionTemplate(transactions);
    this.snapshot = new BoundedSnapshot(transactions);
    this.jdbc =
        JdbcClient.create(requireNonNull(transactions.getDataSource(), "the manager's DataSource"));
  }

  @Override
  public void record(List<ProviderRun> runs, Instant finishedAt) {
    transaction.executeWithoutResult(
        status -> {
          StatementTimeout.STORE.boundTheTransaction(jdbc);
          runs.forEach(run -> replace(run, finishedAt));
        });
  }

  /** The provider's earlier row goes, and its discarded records with it (ON DELETE CASCADE). */
  private void replace(ProviderRun run, Instant finishedAt) {
    String provider = run.provider().name();
    jdbc.sql("DELETE FROM ingestion_status WHERE provider = ?").param(provider).update();
    jdbc.sql(INSERT_STATUS)
        .param(provider)
        .param(run.failure().map(FailureReason::name).orElse(null))
        .param(run.offersUsed())
        .param(run.newestQuotedAt().map(ProviderTime::instantParameter).orElse(null))
        .param(ProviderTime.instantParameter(finishedAt))
        .update();
    run.discarded()
        .forEach(
            (reason, records) ->
                jdbc.sql(INSERT_DISCARDED)
                    .param(provider)
                    .param(reason.name())
                    .param(records)
                    .update());
  }

  @Override
  public Optional<LatestRun> latest() {
    return snapshot.read(this::latestRun);
  }

  /** Each provider's row with the records it discarded, and the end of the latest run. */
  private Optional<LatestRun> latestRun() {
    Map<Provider, Map<DiscardReason, Integer>> discarded = discarded();
    List<ProviderRun> providers =
        jdbc.sql(SELECT_STATUSES).query((rs, rowNum) -> run(rs, discarded)).stream()
            .sorted(Comparator.comparing(ProviderRun::provider))
            .toList();
    return jdbc.sql("SELECT max(finished_at) AS finished_at FROM ingestion_status")
        .query((rs, rowNum) -> ProviderTime.readInstant(rs, "finished_at"))
        .single()
        .map(finishedAt -> new LatestRun(finishedAt, providers));
  }

  /** The records each provider's latest run discarded, by reason; none for a provider without. */
  private Map<Provider, Map<DiscardReason, Integer>> discarded() {
    Map<Provider, Map<DiscardReason, Integer>> discarded = new EnumMap<>(Provider.class);
    jdbc.sql(SELECT_DISCARDED)
        .query(
            (ResultSet rs) -> {
              discarded
                  .computeIfAbsent(
                      Provider.valueOf(rs.getString("provider")),
                      provider -> new EnumMap<>(DiscardReason.class))
                  .put(DiscardReason.valueOf(rs.getString("reason")), rs.getInt("records"));
            });
    return discarded;
  }

  private static ProviderRun run(ResultSet rs, Map<Provider, Map<DiscardReason, Integer>> discarded)
      throws SQLException {
    Provider provider = Provider.valueOf(rs.getString("provider"));
    return new ProviderRun(
        provider,
        Optional.ofNullable(rs.getString("failure")).map(FailureReason::valueOf),
        rs.getInt("offers_used"),
        discarded.getOrDefault(provider, Map.of()),
        ProviderTime.readInstant(rs, "newest_quoted_at"));
  }
}
