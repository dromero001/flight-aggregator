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

import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IngestionMetricsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private Instant now = STARTED_AT;
  private final IngestionMetrics metrics = new IngestionMetrics(meters, () -> now);

  @Test
  void recordsAPublishedRun() {
    metrics.record(
        published(
            new Publication(7, 1234, 2, Instant.parse("2026-12-28T00:00:00Z")),
            fresh(ALPHA),
            kept(BETA, FailureReason.HTTP_ERROR, new IOException("Server error 500")),
            fresh(GAMMA)));
    now = STARTED_AT.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(10));

    assertThat(meters.get("ingestion.runs").tag("outcome", "PUBLISHED").counter().count())
        .isEqualTo(1);
    assertThat(
            meters
                .get("ingestion.duration")
                .tag("outcome", "PUBLISHED")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(90);
    assertThat(gauge("ingestion.itineraries")).isEqualTo(1234);
    assertThat(gauge("ingestion.consecutive.failures")).isZero();
    assertThat(downloads(ALPHA, "FRESH")).isEqualTo(1);
    assertThat(downloads(BETA, "KEPT")).isEqualTo(1);
    assertThat(downloads(GAMMA, "FRESH")).isEqualTo(1);
    // Two days and ten minutes after the run's start: Alpha's and Gamma's newest quotes are 30
    // seconds younger than that, Beta's kept one a day older.
    assertThat(lastFreshSeconds(ALPHA)).isEqualTo(173_370);
    assertThat(lastFreshSeconds(BETA)).isEqualTo(259_800);
    assertThat(lastFreshSeconds(GAMMA)).isEqualTo(173_370);
    // On 2026-11-22 the last partition, 2026-12-27, is 35 days ahead.
    assertThat(gauge("ingestion.partitions.days.ahead")).isEqualTo(35);
    assertThat(gauge("ingestion.partitions.default.rows")).isEqualTo(2);
  }

  /** Why each kept download failed, without its log line: {@code none} for a fresh one. */
  @Test
  void tagsEachDownloadWithItsFailureReason() {
    metrics.record(
        published(
            new Publication(7, 1234, 2, Instant.parse("2026-12-28T00:00:00Z")),
            fresh(ALPHA),
            kept(BETA, FailureReason.TIMEOUT, new IOException("read timed out"))));

    assertThat(downloads(ALPHA, "FRESH", "none")).isEqualTo(1);
    assertThat(downloads(BETA, "KEPT", "TIMEOUT")).isEqualTo(1);
  }

  @Test
  void countsConsecutiveFailuresUntilAPublishedRun() {
    metrics.record(unpublished(IngestionReport.Outcome.CEILING_EXCEEDED, fresh(ALPHA)));
    metrics.record(unpublished(IngestionReport.Outcome.CEILING_EXCEEDED, fresh(ALPHA)));
    metrics.recordFailedRun();

    assertThat(gauge("ingestion.consecutive.failures")).isEqualTo(3);
    assertThat(meters.get("ingestion.runs").tag("outcome", "FAILED").counter().count())
        .isEqualTo(1);

    metrics.record(
        published(new Publication(1, 10, 0, Instant.parse("2026-12-28T00:00:00Z")), fresh(ALPHA)));

    assertThat(gauge("ingestion.consecutive.failures")).isZero();
  }

  /**
   * With every provider down the run still publishes the kept offers, but nothing new came in: it
   * counts as a failure, so that the alert on failures in a row fires during the outage (§6.5).
   */
  @Test
  void countsARunWithNoFreshProviderAsAFailureEvenWhenItPublishes() {
    Publication publication = new Publication(7, 1234, 2, Instant.parse("2026-12-28T00:00:00Z"));
    IOException down = new IOException("Server error 500");

    metrics.record(
        published(
            publication,
            kept(ALPHA, FailureReason.HTTP_ERROR, down),
            kept(BETA, FailureReason.HTTP_ERROR, down),
            kept(GAMMA, FailureReason.HTTP_ERROR, down)));

    assertThat(gauge("ingestion.consecutive.failures")).isEqualTo(1);
    assertThat(meters.get("ingestion.runs").tag("outcome", "PUBLISHED").counter().count())
        .isEqualTo(1);

    metrics.record(published(publication, fresh(ALPHA), kept(BETA, FailureReason.TIMEOUT, down)));

    assertThat(gauge("ingestion.consecutive.failures")).isZero();
  }

  /** Search still reads the last published generation, so its gauges keep it. */
  @Test
  void keepsTheLastPublicationThroughARunThatPublishesNothing() {
    metrics.record(
        published(
            new Publication(7, 1234, 2, Instant.parse("2026-12-28T00:00:00Z")), fresh(ALPHA)));
    metrics.record(unpublished(IngestionReport.Outcome.NOTHING_TO_PUBLISH, fresh(ALPHA)));

    assertThat(gauge("ingestion.itineraries")).isEqualTo(1234);
    assertThat(gauge("ingestion.partitions.default.rows")).isEqualTo(2);
  }

  /**
   * After a restart, the first run gives back how old each provider's data is, from its stored
   * offers' newest quote, even when it keeps them.
   */
  @Test
  void restoresAProvidersFreshnessFromItsStoredOffersAfterARestart() {
    metrics.record(
        unpublished(
            IngestionReport.Outcome.NOTHING_TO_PUBLISH,
            kept(BETA, FailureReason.HTTP_ERROR, new IOException("Server error 500"))));

    // The kept offers were quoted a day before the run started, which is now.
    assertThat(lastFreshSeconds(BETA)).isEqualTo(86_400);
  }

  /**
   * Before the first run nothing is known, which the gauges say with {@code NaN}: an inventory of
   * zero itineraries or partitions zero days ahead would read as an outage.
   */
  @Test
  void readsNothingKnownBeforeTheFirstRun() {
    assertThat(gauge("ingestion.itineraries")).isNaN();
    assertThat(gauge("ingestion.partitions.default.rows")).isNaN();
    assertThat(gauge("ingestion.partitions.days.ahead")).isNaN();
    for (Provider provider : Provider.values()) {
      assertThat(lastFreshSeconds(provider)).as(provider.name()).isNaN();
    }
  }

  private double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  private double downloads(Provider provider, String status) {
    return meters
        .get("ingestion.provider.downloads")
        .tag("provider", provider.name())
        .tag("status", status)
        .counter()
        .count();
  }

  private double downloads(Provider provider, String status, String reason) {
    return meters
        .get("ingestion.provider.downloads")
        .tag("provider", provider.name())
        .tag("status", status)
        .tag("reason", reason)
        .counter()
        .count();
  }

  private double lastFreshSeconds(Provider provider) {
    return meters
        .get("ingestion.provider.last.fresh.seconds")
        .tag("provider", provider.name())
        .gauge()
        .value();
  }
}
