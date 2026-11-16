package com.flightaggregator.bootstrap.ingestion;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.IngestionLauncher;
import com.flightaggregator.bootstrap.observability.CorrelationId;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * One ingestion run, scheduled or on demand ({@link #launch}): it takes the ingestion's lock,
 * ingests, then logs and meters the run's report (§6.10). The report is one line, {@code INFO} for
 * a run that published and {@code WARN} for any other outcome, with each provider's fields under
 * its name, and each provider whose last good offers were kept is one {@code WARN} line with its
 * reason and its failure's stack trace. Anything a run throws, taking the lock included, fails that
 * run alone: it is logged at {@code ERROR} and counted. A runtime exception never escapes, so the
 * next run still comes; an {@code Error} is rethrown after that, to the scheduler.
 */
public final class IngestionJob implements IngestionLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(IngestionJob.class);

  /** The ShedLock row that one instance at a time holds while it runs (§6.2). */
  private static final String LOCK = "ingestion";

  /** Before the next run is due, should an instance die holding the lock. */
  private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(29);

  /**
   * No minimum, for a scheduled run as for a run on demand (the user's decisions after Task 12's
   * review and of 2026-11-16): the lock is released when the run ends, so "Run ingestion now" is
   * refused only while a run really runs.
   */
  private static final Duration LOCK_AT_LEAST_FOR = Duration.ZERO;

  /**
   * The observation of one run: the root span of a run on demand, under Spring's {@code task
   * ingestionJob.run} for a scheduled one. A failed run's span ends with its error.
   */
  public static final String RUN_OBSERVATION = "ingestion.run";

  private final IngestInventory ingestion;
  private final IngestionMetrics metrics;
  private final LockingTaskExecutor locks;
  private final Clock clock;
  private final ObservationRegistry observations;

  /**
   * Each run, scheduled or on demand, is observed as {@code ingestion.run}, the root of a run on
   * demand's trace and, for a scheduled run, under Spring's {@code task ingestionJob.run} (§6.10).
   */
  public IngestionJob(
      IngestInventory ingestion,
      IngestionMetrics metrics,
      LockingTaskExecutor locks,
      Clock clock,
      ObservationRegistry observations) {
    this.ingestion = ingestion;
    this.metrics = metrics;
    this.locks = locks;
    this.clock = clock;
    this.observations = observations;
  }

  /**
   * Runs once, if this instance gets the ingestion's lock, and otherwise logs at {@code INFO} that
   * it skipped the run and returns at once (ShedLock's {@code TaskResult}). The first run starts
   * with the application, and each next one {@code ingestion.interval} after the last ended (§6.4).
   * The lock is taken inside the run's {@code try}, so a database that cannot be reached fails the
   * run like any other failure.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "${ingestion.interval}")
  public void run() {
    runUnderTheLock(() -> {});
  }

  /**
   * A run on demand (§6.8, "Run ingestion now"), the scheduled run's in all but its thread: it runs
   * on a virtual thread of its own, holds the lock until it ends, and this returns once it holds
   * the lock, or at once with {@code false} when another run holds it. A run that fails before it
   * holds the lock, the database out of reach for instance, is logged and counted as any failed
   * run, and this throws.
   */
  @Override
  public boolean launch() {
    CompletableFuture<Boolean> locked = new CompletableFuture<>();
    // A plain thread on purpose, unlike a refresh's: a run is a trace of its own (ingestion.run),
    // not part of the admin call that started it and answers before it ends.
    Thread.ofVirtual()
        .name("ingestion-on-demand")
        .start(
            () -> {
              try {
                boolean skipped = runUnderTheLock(() -> locked.complete(true));
                if (skipped) {
                  locked.complete(false);
                }
              } finally {
                // A no-op once the run held the lock, or was skipped.
                locked.completeExceptionally(
                    new IllegalStateException("The ingestion run failed before it took its lock"));
              }
            });
    return locked.join();
  }

  /**
   * One run, if it gets the lock, {@code onceLocked} first; otherwise it logs at {@code INFO} that
   * it skipped the run and returns at once (ShedLock's {@code TaskResult}). The lock is released
   * when the run ends. Answers whether the run was skipped because another held the lock: a run
   * that ran, or failed, was not.
   */
  private boolean runUnderTheLock(Runnable onceLocked) {
    Observation run = Observation.createNotStarted(RUN_OBSERVATION, observations);
    return run.observe(() -> tryUnderTheLock(run, onceLocked));
  }

  /** A failure is caught here, so it is recorded on the run's observation, which ends with it. */
  private boolean tryUnderTheLock(Observation observation, Runnable onceLocked) {
    // Removed in the finally, after the catch, so that a failed run's ERROR line carries it too.
    MDC.put(CorrelationId.MDC_KEY, UUID.randomUUID().toString());
    Instant startedAt = clock.now();
    try {
      TaskResult<IngestionReport> run =
          locks.executeWithLock(
              () -> {
                onceLocked.run();
                return ingestOnce();
              },
              new LockConfiguration(startedAt, LOCK, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR));
      if (!run.wasExecuted()) {
        LOG.info("Skipped an ingestion run: another run holds its lock");
        return true;
      }
      return false;
    } catch (Throwable e) {
      // A runtime exception or an error: the run throws no checked exception, though ShedLock's
      // signature declares one.
      LOG.atError().setCause(e).log("An ingestion run failed");
      observation.error(e);
      metrics.recordFailedRun(Duration.between(startedAt, clock.now()));
      if (e instanceof Error error) {
        // The JVM may be in trouble: counted and logged, then left to the scheduler, never kept.
        throw error;
      }
      return false;
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  private IngestionReport ingestOnce() {
    IngestionReport report = ingestion.ingest();
    log(report);
    metrics.record(report);
    return report;
  }

  private static void log(IngestionReport report) {
    for (ProviderReport provider : report.providers()) {
      // A provider has a failure exactly when its offers were kept.
      provider.failure().ifPresent(reason -> warnKept(provider, reason));
    }
    report
        .statusNotRecorded()
        .ifPresent(
            failure ->
                LOG.atWarn()
                    .setCause(failure)
                    .log(
                        "Could not record the providers' statuses: the provider-status screen"
                            + " shows an earlier run"));
    // A run that publishes nothing leaves search on an older generation: a warning, not news.
    LoggingEventBuilder line =
        (report.outcome() == IngestionReport.Outcome.PUBLISHED ? LOG.atInfo() : LOG.atWarn())
            .addKeyValue("outcome", report.outcome().name());
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
