package com.flightaggregator.adapter.persistence;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.ProviderRun;
import com.flightaggregator.domain.Provider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * they are read back in one statement, so a read never mixes two runs.
 */
public final class JdbcIngestionStatusStore implements IngestionStatusStore {

  private static final String INSERT_STATUS =
      "INSERT INTO ingestion_status (provider, failure, offers_used, newest_quoted_at, finished_at)"
          + " VALUES (?, ?, ?, ?, ?)";

  private static final String INSERT_DISCARDED =
      "INSERT INTO ingestion_status_discarded (provider, reason, records) VALUES (?, ?, ?)";

  /** Each provider's row once per reason it discarded records for, or once with no reason. */
  private static final String SELECT_LATEST =
      "SELECT s.provider, s.failure, s.offers_used, s.newest_quoted_at, d.reason, d.records"
          + " FROM ingestion_status s"
          + " LEFT JOIN ingestion_status_discarded d ON d.provider = s.provider";

  private final TransactionTemplate transaction;
  private final JdbcClient jdbc;

  /**
   * Its connections and its transactions come from the one manager, so its statements always run in
   * that manager's transaction (E6).
   */
  public JdbcIngestionStatusStore(DataSourceTransactionManager transactions) {
    this.transaction = new TransactionTemplate(transactions);
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
  public List<ProviderRun> latest() {
    Map<Provider, ProviderRun> runs = new EnumMap<>(Provider.class);
    Map<Provider, Map<DiscardReason, Integer>> discarded = new EnumMap<>(Provider.class);
    jdbc.sql(SELECT_LATEST)
        .query(
            (ResultSet rs) -> {
              Provider provider = Provider.valueOf(rs.getString("provider"));
              runs.putIfAbsent(provider, withoutDiscarded(provider, rs));
              String reason = rs.getString("reason");
              if (reason != null) {
                discarded
                    .computeIfAbsent(provider, ignored -> new EnumMap<>(DiscardReason.class))
                    .put(DiscardReason.valueOf(reason), rs.getInt("records"));
              }
            });
    return runs.values().stream()
        .map(run -> withDiscarded(run, discarded.getOrDefault(run.provider(), Map.of())))
        .toList();
  }

  @Override
  public Optional<Instant> lastFinishedAt() {
    return jdbc.sql("SELECT max(finished_at) AS finished_at FROM ingestion_status")
        .query((rs, rowNum) -> instant(rs, "finished_at"))
        .single();
  }

  private static ProviderRun withoutDiscarded(Provider provider, ResultSet rs) throws SQLException {
    return new ProviderRun(
        provider,
        Optional.ofNullable(rs.getString("failure")).map(FailureReason::valueOf),
        rs.getInt("offers_used"),
        Map.of(),
        instant(rs, "newest_quoted_at"));
  }

  private static ProviderRun withDiscarded(ProviderRun run, Map<DiscardReason, Integer> discarded) {
    return new ProviderRun(
        run.provider(), run.failure(), run.offersUsed(), discarded, run.newestQuotedAt());
  }

  private static Optional<Instant> instant(ResultSet rs, String column) throws SQLException {
    return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class))
        .map(OffsetDateTime::toInstant);
  }
}
