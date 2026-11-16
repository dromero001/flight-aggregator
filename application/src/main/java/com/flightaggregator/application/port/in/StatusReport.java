package com.flightaggregator.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the provider-status screen shows (§6.8): the active generation and when the last full run
 * published it, when the next run is due (the last run's end plus the interval), whether a run is
 * going on now, and each provider, in {@code Provider} order. Before the first run there is no time
 * and no provider.
 */
public record StatusReport(
    long generation,
    Optional<Instant> publishedAt,
    Optional<Instant> nextIngestionAt,
    boolean ingestionRunning,
    List<ProviderStatus> providers) {

  public StatusReport {
    providers = List.copyOf(providers);
  }
}
