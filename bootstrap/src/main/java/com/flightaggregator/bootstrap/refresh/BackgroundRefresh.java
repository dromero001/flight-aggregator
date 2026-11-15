package com.flightaggregator.bootstrap.refresh;

import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshStaleRoute;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.bootstrap.observability.CorrelationId;
import com.flightaggregator.domain.Airport;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Starts each stale origin's refresh (§4.1, §6.4) on a virtual thread of its own and returns at
 * once, so the search that asked neither waits for it nor fails with it. Every line of one refresh
 * carries a correlation id of its own, as a run's lines carry the run's. Its report is one {@code
 * INFO} line; whatever it throws, an error included, is one {@code ERROR} line with its stack trace
 * and goes no further. Search asks only after winning the origin's claim (§6.2), so the claim, not
 * this class, bounds the refreshes of one origin.
 */
public final class BackgroundRefresh implements RefreshScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundRefresh.class);

  private final RefreshStaleRoute refreshes;

  public BackgroundRefresh(RefreshStaleRoute refreshes) {
    this.refreshes = refreshes;
  }

  @Override
  public void request(Airport origin) {
    Thread.ofVirtual().name("refresh-" + origin.code()).start(() -> refresh(origin));
  }

  private void refresh(Airport origin) {
    // Removed in the finally, after the catch, so that the ERROR line carries it too.
    MDC.put(CorrelationId.MDC_KEY, UUID.randomUUID().toString());
    try {
      log(refreshes.refresh(origin));
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

  /** The report's fields, each provider's status under its name, as in {@code alpha.status}. */
  private static void log(RefreshReport report) {
    LoggingEventBuilder line =
        LOG.atInfo()
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
