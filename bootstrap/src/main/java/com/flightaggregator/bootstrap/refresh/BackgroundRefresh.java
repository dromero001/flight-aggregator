package com.flightaggregator.bootstrap.refresh;

import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshStaleRoute;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.bootstrap.observability.CorrelationId;
import com.flightaggregator.domain.Airport;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Starts each stale origin's refresh (§4.1, §6.4) on a thread of its own and returns at once, so
 * the search that asked neither waits for it nor fails with it. The thread comes from {@code
 * threads}, created on the search's thread, so the refresh continues the search's trace (§6.10).
 *
 * <p>One refresh at a time runs in the whole application (plan 5's end-of-plan review, I2): each
 * holds ShedLock's {@code refresh} row until it ends, as a run holds {@code ingestion}. A refresh
 * asked for while another runs is skipped before it downloads anything, with one {@code DEBUG} line
 * (every search on a stale origin asks while a refresh runs), and gives its origin's claim back, so
 * the next search that finds the origin stale asks again; nothing is queued. Search asks only after
 * winning the origin's claim (§6.2), so the claim bounds the refreshes of one origin, and the lock
 * those of all origins.
 *
 * <p>Every line of one refresh carries a correlation id of its own, as a run's lines carry the
 * run's, except the circuit breakers' transition lines, logged on a download's thread. Its report
 * is one line, {@code WARN} for a refresh with nothing fresh and {@code INFO} otherwise; whatever it throws, taking the lock included, an error too, is one
 * {@code ERROR} line with its stack trace and goes no further.
 */
public final class BackgroundRefresh implements RefreshScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundRefresh.class);

  /** The ShedLock row that one refresh at a time holds, on any instance. */
  private static final String LOCK = "refresh";

  /**
   * Should an instance die holding the lock: longer than a refresh's worst case, a download of up
   * to 60 s ({@code providers.download-timeout}) then a rewrite whose statements the store bounds
   * at 5 minutes each ({@code StatementTimeout.STORE}). Measured: 37 s for Barcelona and 63 to 85 s
   * for Madrid under a burst of searches (plan 5's closing round 1). A refresh that still outlives
   * it lets a second one start; the publication row keeps their rewrites apart, and the second
   * waits 5 s for it, then fails with its {@code ERROR} line.
   */
  private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(7);

  private final RefreshStaleRoute refreshes;
  private final LockingTaskExecutor locks;
  private final RefreshCoordination coordination;
  private final Clock clock;
  private final ThreadFactory threads;

  public BackgroundRefresh(
      RefreshStaleRoute refreshes,
      LockingTaskExecutor locks,
      RefreshCoordination coordination,
      Clock clock,
      ThreadFactory threads) {
    this.refreshes = refreshes;
    this.locks = locks;
    this.coordination = coordination;
    this.clock = clock;
    this.threads = threads;
  }

  @Override
  public void request(Airport origin) {
    Thread thread = threads.newThread(() -> refresh(origin));
    thread.setName("refresh-" + origin.code());
    thread.start();
  }

  private void refresh(Airport origin) {
    // Removed in the finally, after the catch, so that the ERROR line carries it too.
    MDC.put(CorrelationId.MDC_KEY, UUID.randomUUID().toString());
    try {
      TaskResult<RefreshReport> refresh =
          locks.executeWithLock(
              () -> refreshes.refresh(origin),
              new LockConfiguration(clock.now(), LOCK, LOCK_AT_MOST_FOR, Duration.ZERO));
      if (refresh.wasExecuted()) {
        log(refresh.getResult());
      } else {
        coordination.release(origin);
        LOG.atDebug()
            .addKeyValue("origin", origin.code())
            .log("Skipped the refresh of a stale origin: another refresh is running");
      }
    } catch (Throwable e) {
      // An error too: the refresh's own thread ends here, so nothing above it would report it.
      LOG.atError()
          .addKeyValue("origin", origin.code())
          .setCause(e)
          .log("The refresh of a stale origin failed");
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  /**
   * The report's fields, each provider's status under its name, as in {@code alpha.status}. A
   * refresh with nothing fresh leaves search on the origin's old offers: a warning, as a run that
   * publishes nothing is.
   */
  private static void log(RefreshReport report) {
    LoggingEventBuilder line =
        (report.outcome() == RefreshReport.Outcome.NOTHING_FRESH ? LOG.atWarn() : LOG.atInfo())
            .addKeyValue("origin", report.origin().code())
            .addKeyValue("outcome", report.outcome().name())
            .addKeyValue("generation", report.generation())
            .addKeyValue("itineraries", report.itineraries());
    report.providers().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            provider ->
                line.addKeyValue(
                    provider.getKey().name().toLowerCase(Locale.ROOT) + ".status",
                    provider.getValue().name()));
    line.log("The refresh of a stale origin ended");
  }
}
