package com.flightaggregator.bootstrap.ingestion;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import java.time.Duration;
import java.util.Locale;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * One ingestion run: it takes the ingestion's lock, ingests, then logs and meters the run's report
 * (§6.10). The report is one {@code INFO} line, with each provider's fields under its name, and
 * each provider whose last good offers were kept is one {@code WARN} line with its reason and its
 * failure's stack trace. Any exception of a run, taking the lock included, fails that run alone: it
 * is logged and counted, and never escapes, so the next run still comes.
 */
public final class IngestionJob {

  private static final Logger LOG = LoggerFactory.getLogger(IngestionJob.class);

  /** The ShedLock row that one instance at a time holds while it runs (§6.2). */
  private static final String LOCK = "ingestion";

  /** Before the next run is due, should an instance die holding the lock. */
  private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(29);

  /** So that another instance cannot run again straight after a short run. */
  private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);

  private final IngestInventory ingestion;
  private final IngestionMetrics metrics;
  private final LockingTaskExecutor locks;
  private final Clock clock;

  public IngestionJob(
      IngestInventory ingestion, IngestionMetrics metrics, LockingTaskExecutor locks, Clock clock) {
    this.ingestion = ingestion;
    this.metrics = metrics;
    this.locks = locks;
    this.clock = clock;
  }

  /**
   * Runs once, if this instance gets the ingestion's lock, and returns at once otherwise. The first
   * run starts with the application, and each next one {@code ingestion.interval} after the last
   * ended (§6.4). The lock is taken inside the run's {@code try}, so a database that cannot be
   * reached fails the run like any other failure.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "${ingestion.interval}")
  public void run() {
    try {
      // The Runnable overload: the one that throws no checked exception.
      locks.executeWithLock(
          (Runnable) this::ingestOnce,
          new LockConfiguration(clock.now(), LOCK, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR));
    } catch (RuntimeException e) {
      LOG.atError().setCause(e).log("An ingestion run failed");
      metrics.recordFailedRun();
    }
  }

  private void ingestOnce() {
    IngestionReport report = ingestion.ingest();
    log(report);
    metrics.record(report);
  }

  private static void log(IngestionReport report) {
    for (ProviderReport provider : report.providers()) {
      // A provider has a failure exactly when its offers were kept.
      provider.failure().ifPresent(reason -> warnKept(provider, reason));
    }
    LoggingEventBuilder line = LOG.atInfo().addKeyValue("outcome", report.outcome().name());
    report
        .publication()
        .ifPresent(
            publication ->
                line.addKeyValue("generation", publication.generation())
                    .addKeyValue("itineraries", publication.itineraries()));
    line.addKeyValue("duration", report.duration());
    report.providers().forEach(provider -> addFields(line, provider));
    line.log("Ingestion run ended");
  }

  private static void warnKept(ProviderReport provider, FailureReason reason) {
    LOG.atWarn()
        .addKeyValue("provider", provider.provider().name())
        .addKeyValue("reason", reason.name())
        .setCause(provider.cause().orElse(null))
        .log("Kept the last good offers of a provider that failed");
  }

  /** The provider's fields, each under the provider's name, as in {@code alpha.status}. */
  private static void addFields(LoggingEventBuilder line, ProviderReport provider) {
    String name = provider.provider().name().toLowerCase(Locale.ROOT);
    line.addKeyValue(name + ".status", provider.status().name());
    provider.failure().ifPresent(reason -> line.addKeyValue(name + ".failure", reason.name()));
    line.addKeyValue(name + ".fetched", provider.offersFetched())
        .addKeyValue(name + ".discarded", provider.discarded())
        .addKeyValue(name + ".used", provider.offersUsed())
        .addKeyValue(name + ".lostToDeduplication", provider.lostToDeduplication());
  }
}
