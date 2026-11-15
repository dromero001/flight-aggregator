package com.flightaggregator.application.port.in;

import com.flightaggregator.application.port.out.Publication;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What one run did, with its providers in {@code Provider} order. {@code publication} is what the
 * run published, and is empty unless the outcome is {@code PUBLISHED}.
 */
public record IngestionReport(
    Instant startedAt,
    Duration duration,
    Outcome outcome,
    Optional<Publication> publication,
    List<ProviderReport> providers) {

  public IngestionReport {
    providers = List.copyOf(providers);
  }

  public enum Outcome {
    PUBLISHED,
    NOTHING_TO_PUBLISH,
    CEILING_EXCEEDED
  }
}
