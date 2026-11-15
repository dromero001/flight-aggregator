package com.flightaggregator.bootstrap.ingestion;

import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * The ingestion's meters, recorded from each run's report, with low-cardinality tags only (§6.10):
 * how long runs take and how they end (§6.4), how many runs in a row did not publish and how old
 * each provider's last fresh download is (§6.5), and the partitions' health (§4.3). A gauge whose
 * value is not known, before the first publication or while a provider has nothing stored, reads
 * {@code NaN}. A provider's age comes from its stored offers, so the first run after a restart
 * restores it.
 */
public final class IngestionMetrics {

  /** The outcome of a run that ended with an exception, beside the report's own outcomes. */
  private static final String FAILED = "FAILED";

  /** The {@code reason} of a download that did not fail. */
  private static final String NO_FAILURE = "none";

  private final MeterRegistry meters;
  private final Clock clock;
  private final AtomicReference<Publication> lastPublication = new AtomicReference<>();
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final Map<Provider, AtomicReference<Instant>> newestQuote = new EnumMap<>(Provider.class);

  public IngestionMetrics(MeterRegistry meters, Clock clock) {
    this.meters = meters;
    this.clock = clock;
    publicationGauge("ingestion.itineraries", Publication::itineraries);
    publicationGauge("ingestion.partitions.default.rows", Publication::rowsInDefaultPartition);
    publicationGauge("ingestion.partitions.days.ahead", this::partitionDaysAhead);
    Gauge.builder("ingestion.consecutive.failures", consecutiveFailures, AtomicInteger::get)
        .register(meters);
    for (Provider provider : Provider.values()) {
      AtomicReference<Instant> quote = new AtomicReference<>();
      newestQuote.put(provider, quote);
      Gauge.builder("ingestion.provider.last.fresh.seconds", quote, this::secondsSince)
          .tag("provider", provider.name())
          .register(meters);
    }
  }

  /** A run that ended with a report; every outcome but {@code PUBLISHED} counts as a failure. */
  public void record(IngestionReport report) {
    String outcome = report.outcome().name();
    meters.timer("ingestion.duration", "outcome", outcome).record(report.duration());
    ended(outcome, report.outcome() == IngestionReport.Outcome.PUBLISHED);
    report.publication().ifPresent(lastPublication::set);
    for (ProviderReport provider : report.providers()) {
      meters
          .counter(
              "ingestion.provider.downloads",
              "provider",
              provider.provider().name(),
              "status",
              provider.status().name(),
              "reason",
              provider.failure().map(Enum::name).orElse(NO_FAILURE))
          .increment();
      newestQuote.get(provider.provider()).set(provider.newestQuotedAt().orElse(null));
    }
  }

  /** A run that ended with an exception instead of a report. */
  public void recordFailedRun() {
    ended(FAILED, false);
  }

  /** Counts the run under its outcome; a run that published ends the failures in a row. */
  private void ended(String outcome, boolean published) {
    meters.counter("ingestion.runs", "outcome", outcome).increment();
    if (published) {
      consecutiveFailures.set(0);
    } else {
      consecutiveFailures.incrementAndGet();
    }
  }

  /** A gauge of the last publication, {@code NaN} until there is one. */
  private void publicationGauge(String name, ToDoubleFunction<Publication> value) {
    Gauge.builder(
            name,
            lastPublication,
            last -> last.get() == null ? Double.NaN : value.applyAsDouble(last.get()))
        .register(meters);
  }

  /** The whole days from now until the day partitions end: 37 just after a run (§4.3). */
  private double partitionDaysAhead(Publication publication) {
    return Duration.between(clock.now(), publication.partitionedUntil()).toDays();
  }

  /**
   * The age of a provider's last fresh download: of the newest quote among its stored offers, which
   * that download stored, as the last run reported it.
   */
  private double secondsSince(AtomicReference<Instant> quote) {
    Instant at = quote.get();
    return at == null ? Double.NaN : Duration.between(at, clock.now()).toMillis() / 1000.0;
  }
}
