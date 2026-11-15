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
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshStaleRoute;
import com.flightaggregator.bootstrap.observability.CorrelationId;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Provider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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

  @BeforeEach
  void captureTheLog() {
    log.start();
    logger.addAppender(log);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(log);
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
    BackgroundRefresh scheduler = new BackgroundRefresh(failing);

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
        new BackgroundRefresh(
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
   * itineraries rewritten, and each provider's status under its name, as a run's line has them.
   */
  @Test
  void logsTheReport() throws Exception {
    new BackgroundRefresh(BackgroundRefreshTest::refreshed).request(MADRID);

    assertThat(awaitLines(1))
        .extracting(
            ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage, line -> fields(line))
        .containsExactly(
            tuple(
                Level.INFO,
                "The refresh of a stale origin ended",
                "origin=MAD outcome=REFRESHED generation=8 itineraries=3"
                    + " alpha.status=FRESH beta.status=KEPT"));
  }

  /**
   * Every line of one refresh carries an id of its own, as a run's lines carry the run's, the
   * failure's line included; the search that asked keeps its own logging context.
   */
  @Test
  void logsEachRefreshUnderItsOwnCorrelationId() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "the search's");
    try {
      new BackgroundRefresh(BackgroundRefreshTest::refreshed).request(MADRID);
      new BackgroundRefresh(
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

  /** A refresh of the origin that rewrote 3 itineraries into generation 8, Beta kept. */
  private static RefreshReport refreshed(Airport origin) {
    return new RefreshReport(
        origin,
        RefreshReport.Outcome.REFRESHED,
        8,
        3,
        Map.of(
            Provider.BETA,
            ProviderReport.Status.KEPT,
            Provider.ALPHA,
            ProviderReport.Status.FRESH));
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
