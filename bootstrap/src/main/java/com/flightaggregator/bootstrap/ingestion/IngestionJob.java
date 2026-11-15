package com.flightaggregator.bootstrap.ingestion;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.FailureReason;
import java.util.Locale;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * One ingestion run: it ingests, then logs and meters the run's report (§6.10). The report is one
 * {@code INFO} line, with each provider's fields under its name, and each provider whose last good
 * offers were kept is one {@code WARN} line with its reason and its failure's stack trace. Any
 * exception of a run fails that run alone: it is logged and counted, and never escapes, so the next
 * run still comes. Not final: ShedLock's lock is a proxy that subclasses it.
 */
public class IngestionJob {

  private static final Logger LOG = LoggerFactory.getLogger(IngestionJob.class);

  private final IngestInventory ingestion;
  private final IngestionMetrics metrics;

  public IngestionJob(IngestInventory ingestion, IngestionMetrics metrics) {
    this.ingestion = ingestion;
    this.metrics = metrics;
  }

  /**
   * Runs once, if this instance gets the ingestion's lock, and returns at once otherwise. The first
   * run starts with the application, and each next one {@code ingestion.interval} after the last
   * ended (§6.4). The lock ends with the run, but never before a minute, nor after 29: before the
   * next run is due.
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "${ingestion.interval}")
  @SchedulerLock(name = "ingestion", lockAtMostFor = "PT29M", lockAtLeastFor = "PT1M")
  public void run() {
    try {
      IngestionReport report = ingestion.ingest();
      log(report);
      metrics.record(report);
    } catch (RuntimeException e) {
      LOG.atError().setCause(e).log("An ingestion run failed");
      metrics.recordFailedRun();
    }
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
