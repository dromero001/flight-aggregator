package com.flightaggregator.application.port.in;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** What one run did, with its providers in {@code Provider} order. */
public record IngestionReport(
    Instant startedAt,
    Duration duration,
    Outcome outcome,
    long generation,
    long itineraries,
    List<ProviderReport> providers) {

  public IngestionReport {
    providers = List.copyOf(providers);
  }

  public enum Outcome {
    PUBLISHED,
    NO_OFFERS,
    CEILING_EXCEEDED
  }
}
