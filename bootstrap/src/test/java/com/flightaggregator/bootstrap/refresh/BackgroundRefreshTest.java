package com.flightaggregator.bootstrap.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshReport.ProviderRefresh;
import com.flightaggregator.application.port.in.RefreshStaleRoute;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.bootstrap.observability.CorrelationId;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The log is read as Logback events on the class's logger, as in {@code IngestionJobTest}. A
 * refresh logs on its own thread, so each test waits for the lines it expects, counted as they are
 * appended, rather than for a time.
 */
class BackgroundRefreshTest {

  private static final Airport MADRID = new Airport("MAD");
  private static final Airport LONDON = new Airport("LHR");
  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

  private final RecordingClaims claims = new RecordingClaims();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** What the clock reads: a refresh moves it on to take time. */
  private volatile Instant now = NOW;

  private final Logger logger = (Logger) LoggerFactory.getLogger(BackgroundRefresh.class);
  private final Semaphore appended = new Semaphore(0);
  private final ListAppender<ILoggingEvent> log =
      new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
          super.append(event);
          appended.release();
        }
      };

  /** The logger's own level before the test, restored after it. */
  private Level configuredLevel;

  /** Captured down to DEBUG, the level of a skipped refresh's line. */
  @BeforeEach
  void captureTheLog() {
    configuredLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    log.start();
    logger.addAppender(log);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(log);
    logger.setLevel(configuredLevel);
  }

  /** The lines logged, once there are this many. */
  private List<ILoggingEvent> awaitLines(int lines) throws InterruptedException {
    assertThat(appended.tryAcquire(lines, 10, TimeUnit.SECONDS))
        .as("%d lines logged", lines)
        .isTrue();
    return List.copyOf(log.list);
  }

  /**
   * A search asks for the refresh on its own request thread: whatever the refresh throws, a runtime
   * exception or an error, is one ERROR line naming the origin, with the failure, and never reaches
   * the search.
   */
  @ParameterizedTest
  @MethodSource("failures")
  void logsAFailureWithoutThrowing(Throwable failure, RefreshStaleRoute failing) throws Exception {
    BackgroundRefresh scheduler = scheduler(failing);

    assertThatCode(() -> scheduler.request(MADRID)).doesNotThrowAnyException();

    assertThat(awaitLines(1))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.getLevel()).isEqualTo(Level.ERROR);
              assertThat(fields(line)).isEqualTo("origin=MAD");
              assertThat(((ThrowableProxy) line.getThrowableProxy()).getThrowable())
                  .isSameAs(failure);
            });
  }

  /**
   * The search that asks for the refresh does not wait for it: the request returns while the
   * refresh, on a virtual thread of its own, is still downloading.
   */
  @Test
  void returnsBeforeTheRefreshEnds() throws Exception {
    CountDownLatch released = new CountDownLatch(1);
    CompletableFuture<Boolean> onAVirtualThread = new CompletableFuture<>();
    BackgroundRefresh scheduler =
        scheduler(
            origin -> {
              onAVirtualThread.complete(Thread.currentThread().isVirtual());
              awaitRelease(released);
              return refreshed(origin);
            });
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> scheduler.request(MADRID));

      assertThat(onAVirtualThread.get(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      released.countDown();
    }
    // Its line, so that it has ended before the next test captures the log.
    awaitLines(1);
  }

  /**
   * The refresh's report is one INFO line: the origin, the outcome, the generation and the
   * itineraries rewritten, and each provider's status under its name, with the reason a kept
   * provider's download failed, as a run's line has them (plan 5 Task 7's extra).
   */
  @Test
  void logsTheReport() throws Exception {
    scheduler(BackgroundRefreshTest::refreshed).request(MADRID);

    assertThat(awaitLines(1))
        .extracting(
            ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage, line -> fields(line))
        .containsExactly(
            tuple(
                Level.INFO,
                "The refresh of a stale origin ended",
                "origin=MAD outcome=REFRESHED generation=8 itineraries=3"
                    + " alpha.status=FRESH beta.status=KEPT beta.failure=TIMEOUT"));
  }

  /**
   * A refresh with no fresh download leaves search on the origin's old offers: its line is a
   * warning, as a run's that publishes nothing is (plan 5 Task 7's extra).
   */
  @Test
  void logsARefreshWithNothingFreshAtWarn() throws Exception {
    scheduler(
            origin ->
                new RefreshReport(
                    origin,
                    RefreshReport.Outcome.NOTHING_FRESH,
                    0,
                    0,
                    Map.of(Provider.ALPHA, ProviderRefresh.kept(FailureReason.HTTP_ERROR))))
        .request(MADRID);

    assertThat(awaitLines(1))
        .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
        .containsExactly(tuple(Level.WARN, "The refresh of a stale origin ended"));
  }

  /**
   * Each refresh is counted and timed under its outcome (plan 5 Task 7's extra), from before it
   * takes the lock until its report.
   */
  @Test
  void metersEachRefreshUnderItsOutcome() throws Exception {
    scheduler(
            origin -> {
              now = NOW.plusSeconds(37);
              return refreshed(origin);
            })
        .request(MADRID);

    awaitLines(1);
    assertThat(meters.get("refresh.runs").tag("outcome", "REFRESHED").counter().count())
        .isEqualTo(1);
    assertThat(
            meters
                .get("refresh.duration")
                .tag("outcome", "REFRESHED")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(37);
  }

  /** A refresh that throws is counted and timed as {@code FAILED}, as a run that throws is. */
  @Test
  void metersAFailedRefreshAsFailed() throws Exception {
    scheduler(
            origin -> {
              now = NOW.plusSeconds(5);
              throw new IllegalStateException("lock timeout");
            })
        .request(MADRID);

    awaitLines(1);
    assertThat(meters.get("refresh.runs").tag("outcome", "FAILED").counter().count()).isEqualTo(1);
    assertThat(
            meters
                .get("refresh.duration")
                .tag("outcome", "FAILED")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(5);
  }

  /**
   * Every line of one refresh carries an id of its own, as a run's lines carry the run's, the
   * failure's line included; the search that asked keeps its own logging context.
   */
  @Test
  void logsEachRefreshUnderItsOwnCorrelationId() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "the search's");
    try {
      scheduler(BackgroundRefreshTest::refreshed).request(MADRID);
      scheduler(
              origin -> {
                throw new IllegalStateException("lock timeout");
              })
          .request(MADRID);

      assertThat(awaitLines(2))
          .extracting(line -> line.getMDCPropertyMap().get(CorrelationId.MDC_KEY))
          .doesNotContainNull()
          .doesNotHaveDuplicates()
          .doesNotContain("the search's");
      assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("the search's");
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  /**
   * One refresh at a time in the whole application (plan 5's end-of-plan review, I2): one asked for
   * while another runs is skipped before it downloads anything, and says so at DEBUG: while a
   * refresh runs, every search on a stale origin asks, so an INFO line would be one per search.
   */
  @Test
  void skipsARefreshAskedForWhileAnotherRuns() throws Exception {
    List<Airport> refreshed = new CopyOnWriteArrayList<>();

    assertThat(askForLondonWhileMadridIsRefreshed(refreshed))
        .extracting(
            ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage, line -> fields(line))
        .containsExactly(
            tuple(
                Level.DEBUG,
                "Skipped the refresh of a stale origin: another refresh is running",
                "origin=LHR"));
    assertThat(refreshed).containsExactly(MADRID);
  }

  /**
   * A skipped refresh is counted as {@code SKIPPED} (plan 5's closing round 1), so the pressure on
   * the one refresh at a time shows; its DEBUG line alone would be off in production.
   */
  @Test
  void countsASkippedRefresh() throws Exception {
    askForLondonWhileMadridIsRefreshed(new CopyOnWriteArrayList<>());

    assertThat(meters.get("refresh.runs").tag("outcome", "SKIPPED").counter().count()).isEqualTo(1);
  }

  /**
   * A skipped refresh gives its origin's claim back, so the next search that finds the origin stale
   * asks again at once, instead of after the claim expires (no queue).
   */
  @Test
  void givesTheClaimOfASkippedRefreshBack() throws Exception {
    askForLondonWhileMadridIsRefreshed(new CopyOnWriteArrayList<>());

    assertThat(claims.released).containsExactly(LONDON);
  }

  /**
   * The lock is ShedLock's row {@code refresh}: released as soon as the refresh ends, so the next
   * search's refresh may start at once, and held at most 7 minutes, should the instance die holding
   * it: longer than a refresh's worst case, a 60 s download then a rewrite under the store's 5
   * minute statement bound.
   */
  @Test
  void holdsTheRefreshLockUntilTheRefreshEndsAndAtMostSevenMinutes() throws Exception {
    List<LockConfiguration> taken = new CopyOnWriteArrayList<>();
    LockingTaskExecutor recording =
        new DefaultLockingTaskExecutor(
            configuration -> {
              taken.add(configuration);
              return Optional.of(() -> {});
            });

    new BackgroundRefresh(
            BackgroundRefreshTest::refreshed,
            recording,
            claims,
            () -> NOW,
            Thread.ofVirtual().factory(),
            new RefreshMetrics(meters))
        .request(MADRID);

    awaitLines(1);
    assertThat(taken)
        .singleElement()
        .satisfies(
            configuration -> {
              assertThat(configuration.getName()).isEqualTo("refresh");
              assertThat(configuration.getLockAtLeastFor()).isZero();
              assertThat(configuration.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(7));
              assertThat(configuration.getLockAtMostUntil())
                  .isEqualTo(NOW.plus(Duration.ofMinutes(7)));
            });
  }

  /**
   * Asks for London's refresh while Madrid's runs, and returns the first line logged meanwhile.
   * Madrid's refresh then ends, and its line is awaited, so that it has ended before the next test
   * captures the log. Each origin a refresh started for is added to {@code refreshed}.
   */
  private List<ILoggingEvent> askForLondonWhileMadridIsRefreshed(List<Airport> refreshed)
      throws InterruptedException {
    CountDownLatch madridStarted = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    BackgroundRefresh scheduler =
        scheduler(
            origin -> {
              refreshed.add(origin);
              if (origin.equals(MADRID)) {
                madridStarted.countDown();
                awaitRelease(released);
              }
              return refreshed(origin);
            });
    List<ILoggingEvent> meanwhile;
    try {
      scheduler.request(MADRID);
      assertThat(madridStarted.await(10, TimeUnit.SECONDS)).isTrue();

      scheduler.request(LONDON);
      meanwhile = awaitLines(1);
    } finally {
      released.countDown();
    }
    awaitLines(1);
    return meanwhile;
  }

  /**
   * A scheduler over a lock of its own that one refresh at a time holds, as ShedLock's row in
   * PostgreSQL does, and over the claims this test records.
   */
  private BackgroundRefresh scheduler(RefreshStaleRoute refreshes) {
    AtomicBoolean held = new AtomicBoolean();
    LockingTaskExecutor oneAtATime =
        new DefaultLockingTaskExecutor(
            configuration ->
                held.compareAndSet(false, true)
                    ? Optional.of(() -> held.set(false))
                    : Optional.empty());
    return new BackgroundRefresh(
        refreshes,
        oneAtATime,
        claims,
        () -> now,
        Thread.ofVirtual().factory(),
        new RefreshMetrics(meters));
  }

  /** The claims given back; search, not the scheduler, claims and marks origins. */
  private static final class RecordingClaims implements RefreshCoordination {

    private final List<Airport> released = new CopyOnWriteArrayList<>();

    @Override
    public Optional<Instant> refreshedAt(Airport origin) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean claim(Airport origin) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void release(Airport origin) {
      released.add(origin);
    }

    @Override
    public void refreshed(Airport origin, Instant at) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A refresh of the origin that rewrote 3 itineraries into generation 8, Beta kept on a timeout.
   */
  private static RefreshReport refreshed(Airport origin) {
    return new RefreshReport(
        origin,
        RefreshReport.Outcome.REFRESHED,
        8,
        3,
        Map.of(
            Provider.BETA,
            ProviderRefresh.kept(FailureReason.TIMEOUT),
            Provider.ALPHA,
            ProviderRefresh.FRESH));
  }

  private static void awaitRelease(CountDownLatch released) {
    try {
      released.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static Stream<Arguments> failures() {
    IllegalStateException lockTimeout = new IllegalStateException("lock timeout");
    OutOfMemoryError outOfMemory = new OutOfMemoryError("Java heap space");
    return Stream.of(
        arguments(
            lockTimeout,
            (RefreshStaleRoute)
                origin -> {
                  throw lockTimeout;
                }),
        arguments(
            outOfMemory,
            (RefreshStaleRoute)
                origin -> {
                  throw outOfMemory;
                }));
  }

  private static String fields(ILoggingEvent event) {
    return String.join(
        " ", event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList());
  }
}
