package com.flightaggregator.bootstrap.ingestion;

import static com.flightaggregator.bootstrap.ingestion.Reports.STARTED_AT;
import static com.flightaggregator.bootstrap.ingestion.Reports.fresh;
import static com.flightaggregator.bootstrap.ingestion.Reports.kept;
import static com.flightaggregator.bootstrap.ingestion.Reports.published;
import static com.flightaggregator.bootstrap.ingestion.Reports.unpublished;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.Publication;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.support.LockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * The log is read as Logback events on the job's logger, as in {@code LoggedDiscardedRecordsTest}.
 */
class IngestionJobTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final IngestionMetrics metrics = new IngestionMetrics(meters, () -> STARTED_AT);
  private final Logger logger = (Logger) LoggerFactory.getLogger(IngestionJob.class);
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void captureTheLog() {
    log.start();
    logger.addAppender(log);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(log);
  }

  /**
   * PostgreSQL's {@code lock_timeout} (SQLState 55P03) reaches the job untranslated. The run fails,
   * and the next one still runs.
   */
  @Test
  void keepsSchedulingAfterAnException() {
    UncategorizedSQLException lockTimeout =
        new UncategorizedSQLException(
            "replaceInventory",
            "TRUNCATE itinerary_b",
            new SQLException("canceling statement due to lock timeout", "55P03"));
    Iterator<Supplier<IngestionReport>> runs =
        List.<Supplier<IngestionReport>>of(
                () -> {
                  throw lockTimeout;
                },
                () ->
                    published(
                        new Publication(1, 10, 0, Instant.parse("2026-12-28T00:00:00Z")),
                        fresh(ALPHA)))
            .iterator();
    IngestionJob job = job(() -> runs.next().get());

    assertThatCode(job::run).doesNotThrowAnyException();
    job.run();

    assertThat(log.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .singleElement()
        .satisfies(
            event ->
                assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable())
                    .isSameAs(lockTimeout));
    assertThat(runs("FAILED")).isEqualTo(1);
    assertThat(runs("PUBLISHED")).isEqualTo(1);
  }

  /**
   * An error, such as running out of memory, is not swallowed: the run is logged and counted as
   * failed, and the error goes on to the scheduler.
   */
  @Test
  void countsAndRethrowsAnError() {
    OutOfMemoryError outOfMemory = new OutOfMemoryError("Java heap space");
    IngestionJob job =
        job(
            () -> {
              throw outOfMemory;
            });

    assertThatThrownBy(job::run).isSameAs(outOfMemory);

    assertThat(log.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .singleElement()
        .satisfies(
            event ->
                assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable())
                    .isSameAs(outOfMemory));
    assertThat(runs("FAILED")).isEqualTo(1);
    assertThat(meters.get("ingestion.consecutive.failures").gauge().value()).isEqualTo(1);
  }

  /** A failed run is timed like any other, under its outcome, from the run's start to its end. */
  @Test
  void timesARunThatFails() {
    Iterator<Instant> instants = List.of(STARTED_AT, STARTED_AT.plusSeconds(5)).iterator();
    IngestionJob job =
        new IngestionJob(
            () -> {
              throw new IllegalStateException("database down");
            },
            metrics,
            new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> {})),
            instants::next,
            ObservationRegistry.NOOP);

    job.run();

    Timer failed = meters.get("ingestion.duration").tag("outcome", "FAILED").timer();
    assertThat(failed.count()).isEqualTo(1);
    assertThat(failed.totalTime(TimeUnit.SECONDS)).isEqualTo(5);
  }

  /**
   * PostgreSQL is unreachable when the run takes its lock: the run fails before it starts, and is
   * logged and counted as any failed run is, so that an outage cannot keep the failures flat.
   */
  @Test
  void countsAFailureToTakeTheLockAsAFailedRun() {
    LockException unreachable =
        new LockException(
            "Unexpected exception when locking",
            new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));
    IngestionJob job =
        new IngestionJob(
            () -> published(new Publication(1, 10, 0, Instant.parse("2026-12-28T00:00:00Z"))),
            metrics,
            new DefaultLockingTaskExecutor(
                configuration -> {
                  throw unreachable;
                }),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    assertThatCode(job::run).doesNotThrowAnyException();

    assertThat(log.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .singleElement()
        .satisfies(
            event ->
                assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable())
                    .isSameAs(unreachable));
    assertThat(runs("FAILED")).isEqualTo(1);
  }

  /**
   * Another instance holds the lock: this one skips its run, and says so, so that an instance that
   * skips does not look idle. A skipped run is neither a success nor a failure.
   */
  @Test
  void logsARunSkippedBecauseAnotherInstanceHoldsTheLock() {
    AtomicBoolean ingested = new AtomicBoolean();
    IngestionJob job =
        new IngestionJob(
            () -> {
              ingested.set(true);
              return published(new Publication(1, 10, 0, Instant.parse("2026-12-28T00:00:00Z")));
            },
            metrics,
            new DefaultLockingTaskExecutor(configuration -> Optional.empty()),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    job.run();

    assertThat(ingested).isFalse();
    assertThat(log.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage())
                  .isEqualTo("Skipped an ingestion run: another run holds its lock");
            });
    assertThat(meters.find("ingestion.runs").counters()).isEmpty();
  }

  /**
   * The lock ends with the run, with no minimum (the user's decision of 2026-11-16, as for a run on
   * demand), so "An ingestion is already running." is answered only while a run is running; and
   * never after 29 minutes, before the next run is due.
   */
  @Test
  void takesTheIngestionLockForAtMost29MinutesWithNoMinimum() {
    List<LockConfiguration> taken = new ArrayList<>();
    IngestionJob job =
        new IngestionJob(
            () -> published(new Publication(1, 10, 0, Instant.parse("2026-12-28T00:00:00Z"))),
            metrics,
            new DefaultLockingTaskExecutor(
                configuration -> {
                  taken.add(configuration);
                  return Optional.of(() -> {});
                }),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    job.run();

    assertThat(taken)
        .singleElement()
        .satisfies(
            configuration -> {
              assertThat(configuration.getName()).isEqualTo("ingestion");
              assertThat(configuration.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(29));
              assertThat(configuration.getLockAtLeastFor()).isEqualTo(Duration.ZERO);
              assertThat(configuration.getLockAtMostUntil())
                  .isEqualTo(STARTED_AT.plus(Duration.ofMinutes(29)));
            });
  }

  /** One line per run, and one warning per provider whose last good offers were kept. */
  @Test
  void logsTheRunAndEachKeptProvider() {
    IOException serverError = new IOException("Server error 500");
    IngestionJob job =
        job(
            () ->
                published(
                    new Publication(7, 1234, 2, Instant.parse("2026-12-28T00:00:00Z")),
                    fresh(ALPHA),
                    kept(BETA, FailureReason.HTTP_ERROR, serverError),
                    fresh(GAMMA)));

    job.run();

    assertThat(log.list)
        .extracting(ILoggingEvent::getLevel, IngestionJobTest::fields)
        .containsExactly(
            tuple(Level.WARN, "provider=BETA reason=HTTP_ERROR"),
            tuple(
                Level.INFO,
                "outcome=PUBLISHED generation=7 itineraries=1234 duration=PT1M30S"
                    + " alpha.status=FRESH alpha.fetched=4 alpha.discarded={PRICE=1}"
                    + " alpha.used=3 alpha.lostToDeduplication=1"
                    + " beta.status=KEPT beta.failure=HTTP_ERROR beta.fetched=0 beta.discarded={}"
                    + " beta.used=2 beta.lostToDeduplication=0"
                    + " gamma.status=FRESH gamma.fetched=4 gamma.discarded={PRICE=1}"
                    + " gamma.used=3 gamma.lostToDeduplication=1"));
    assertThat(((ThrowableProxy) log.list.getFirst().getThrowableProxy()).getThrowable())
        .isSameAs(serverError);
  }

  /**
   * Every line of one run carries the same run id, in the logging context (§6.10), a new one each
   * run, and the context is left as it was found.
   */
  @Test
  void tagsEveryLineOfARunWithItsOwnRunId() {
    IngestionJob job =
        job(
            () ->
                published(
                    new Publication(7, 1234, 2, Instant.parse("2026-12-28T00:00:00Z")),
                    fresh(ALPHA),
                    kept(BETA, FailureReason.HTTP_ERROR, new IOException("Server error 500"))));

    job.run();
    job.run();

    List<String> runIds = log.list.stream().map(IngestionJobTest::runId).toList();
    assertThat(runIds).hasSize(4).doesNotContainNull();
    assertThat(runIds.get(0)).isEqualTo(runIds.get(1));
    assertThat(runIds.get(2)).isEqualTo(runIds.get(3)).isNotEqualTo(runIds.get(0));
    assertThat(MDC.get("correlation.id")).isNull();
  }

  /** A failed run's line is one of the run's lines too. */
  @Test
  void tagsTheLineOfAFailedRunWithItsRunId() {
    job(() -> {
          throw new IllegalStateException("database down");
        })
        .run();

    assertThat(log.list).singleElement().extracting(IngestionJobTest::runId).isNotNull();
  }

  /** A run that publishes nothing is a warning, so it is not lost among the runs that did. */
  @ParameterizedTest
  @EnumSource(
      value = IngestionReport.Outcome.class,
      names = {"NOTHING_TO_PUBLISH", "CEILING_EXCEEDED"})
  void logsARunThatPublishedNothingAsAWarning(IngestionReport.Outcome outcome) {
    job(() -> unpublished(outcome, fresh(ALPHA))).run();

    assertThat(log.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).isEqualTo("Ingestion run ended");
            });
  }

  /**
   * A run whose providers' statuses could not be recorded still ended as it did; the failure is a
   * warning of its own, with its cause, since the provider-status screen now shows an older run.
   */
  @Test
  void warnsOfStatusesNotRecorded() {
    IllegalStateException databaseDown = new IllegalStateException("database down");
    job(() ->
            unpublished(IngestionReport.Outcome.NOTHING_TO_PUBLISH, fresh(ALPHA))
                .withStatusNotRecorded(databaseDown))
        .run();

    assertThat(log.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "Could not record the providers' statuses: the provider-status screen shows an"
                + " earlier run",
            "Ingestion run ended");
    assertThat(((ThrowableProxy) log.list.getFirst().getThrowableProxy()).getThrowable())
        .isSameAs(databaseDown);
    assertThat(runs("NOTHING_TO_PUBLISH")).isEqualTo(1);
  }

  /**
   * A run started on demand (§6.8, "Run ingestion now") takes the scheduled run's lock: the launch
   * returns once the run holds it, the run goes on in the background on a virtual thread, as the
   * scheduled run does, with its run id, and the lock is released when it ends. The test holds the
   * run until it has checked the lock is still held, so nothing depends on timing.
   */
  @Test
  void launchesARunUnderTheLock() throws InterruptedException {
    List<String> locked = new ArrayList<>();
    CountDownLatch unlocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> runThread = new AtomicReference<>();
    AtomicReference<String> runId = new AtomicReference<>();
    IngestionJob job =
        new IngestionJob(
            () -> {
              runThread.set(Thread.currentThread());
              runId.set(MDC.get("correlation.id"));
              await(release);
              return unpublished(IngestionReport.Outcome.NOTHING_TO_PUBLISH, fresh(ALPHA));
            },
            metrics,
            new DefaultLockingTaskExecutor(
                configuration -> {
                  locked.add(configuration.getName());
                  return Optional.of(unlocked::countDown);
                }),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    assertThat(job.launch()).isTrue();

    assertThat(locked).containsExactly("ingestion");
    assertThat(unlocked.getCount()).as("the lock is held while the run goes on").isOne();
    release.countDown();
    assertThat(unlocked.await(10, TimeUnit.SECONDS)).as("the lock released at the end").isTrue();
    assertThat(runThread.get().isVirtual()).isTrue();
    assertThat(runThread.get()).isNotSameAs(Thread.currentThread());
    assertThat(runId.get()).isNotNull();
    assertThat(runs("NOTHING_TO_PUBLISH")).isEqualTo(1);
  }

  /**
   * The user's decision after Task 12's review (C-I1): a run on demand holds the lock only while it
   * runs, with no minimum, so "Run ingestion now" is accepted again as soon as the last one ended.
   * The lock provider keeps the row until the time ShedLock asks it to, as the JDBC one does: the
   * run's end, or its {@code lockAtLeastFor} when that is later; the clock never moves.
   */
  @Test
  void acceptsALaunchRightAfterTheLastLaunchedRunEnded() throws InterruptedException {
    AtomicReference<Instant> lockedUntil = new AtomicReference<>(Instant.MIN);
    List<CountDownLatch> unlocks = List.of(new CountDownLatch(1), new CountDownLatch(1));
    AtomicInteger locks = new AtomicInteger();
    IngestionJob job =
        new IngestionJob(
            () -> unpublished(IngestionReport.Outcome.NOTHING_TO_PUBLISH),
            metrics,
            new DefaultLockingTaskExecutor(
                configuration -> {
                  if (STARTED_AT.isBefore(lockedUntil.get())) {
                    return Optional.empty();
                  }
                  lockedUntil.set(configuration.getLockAtMostUntil());
                  CountDownLatch unlocked = unlocks.get(locks.getAndIncrement());
                  return Optional.of(
                      () -> {
                        lockedUntil.set(configuration.getLockAtLeastUntil());
                        unlocked.countDown();
                      });
                }),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    assertThat(job.launch()).isTrue();
    assertThat(unlocks.getFirst().await(10, TimeUnit.SECONDS)).as("the first run ended").isTrue();

    assertThat(job.launch()).as("a launch once the first run ended").isTrue();
    assertThat(unlocks.getLast().await(10, TimeUnit.SECONDS)).as("the second run ended").isTrue();
  }

  /**
   * Another run holds the lock, a scheduled one or one on demand, on this instance or another: the
   * launch answers {@code false} at once and runs nothing, and the skip is logged as a scheduled
   * run's is.
   */
  @Test
  void refusesToLaunchWhileTheLockIsHeld() {
    AtomicBoolean ingested = new AtomicBoolean();
    IngestionJob job =
        new IngestionJob(
            () -> {
              ingested.set(true);
              return unpublished(IngestionReport.Outcome.NOTHING_TO_PUBLISH);
            },
            metrics,
            new DefaultLockingTaskExecutor(configuration -> Optional.empty()),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    assertThat(job.launch()).isFalse();

    assertThat(ingested).isFalse();
    assertThat(log.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .isEqualTo("Skipped an ingestion run: another run holds its lock");
  }

  /**
   * A launch whose run cannot take the lock, PostgreSQL out of reach, is not "another run holds
   * it": the launch fails, and the run is logged and counted as any failed run.
   */
  @Test
  void failsALaunchWhoseRunCannotTakeTheLock() {
    LockException unreachable =
        new LockException(
            "Unexpected exception when locking",
            new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));
    IngestionJob job =
        new IngestionJob(
            () -> unpublished(IngestionReport.Outcome.NOTHING_TO_PUBLISH),
            metrics,
            new DefaultLockingTaskExecutor(
                configuration -> {
                  throw unreachable;
                }),
            () -> STARTED_AT,
            ObservationRegistry.NOOP);

    assertThatThrownBy(job::launch).isInstanceOf(CompletionException.class);

    assertThat(log.list)
        .filteredOn(event -> event.getLevel() == Level.ERROR)
        .singleElement()
        .satisfies(
            event ->
                assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable())
                    .isSameAs(unreachable));
    assertThat(runs("FAILED")).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("The test never released the run");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The job over this ingestion, its lock always free. */
  private IngestionJob job(IngestInventory ingestion) {
    return new IngestionJob(
        ingestion,
        metrics,
        new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> {})),
        () -> STARTED_AT,
        ObservationRegistry.NOOP);
  }

  private static String runId(ILoggingEvent event) {
    return event.getMDCPropertyMap().get("correlation.id");
  }

  private static String fields(ILoggingEvent event) {
    return String.join(
        " ", event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList());
  }

  private double runs(String outcome) {
    return meters.get("ingestion.runs").tag("outcome", outcome).counter().count();
  }
}
