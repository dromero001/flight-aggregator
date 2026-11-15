package com.flightaggregator.application.port.in;

import com.flightaggregator.application.port.out.Publication;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What one run did, with its providers in {@code Provider} order. {@code publication} is what the
 * run published: present exactly when the outcome is {@code PUBLISHED}, which the constructor
 * checks. {@code statusNotRecorded} is why the providers' statuses could not be recorded for the
 * provider-status screen (Q7-B), which never fails the run, so that a caller can log it (the
 * application itself never logs); empty when they were.
 */
public record IngestionReport(
    Instant startedAt,
    Duration duration,
    Outcome outcome,
    Optional<Publication> publication,
    List<ProviderReport> providers,
    Optional<RuntimeException> statusNotRecorded) {

  public IngestionReport {
    boolean published = outcome == Outcome.PUBLISHED;
    if (published && publication.isEmpty()) {
      throw new IllegalArgumentException("A PUBLISHED run must have a publication");
    }
    if (!published && publication.isPresent()) {
      throw new IllegalArgumentException("A " + outcome + " run cannot have a publication");
    }
    providers = List.copyOf(providers);
  }

  /** The same report, its providers' statuses not recorded for {@code failure}. */
  public IngestionReport withStatusNotRecorded(RuntimeException failure) {
    return new IngestionReport(
        startedAt, duration, outcome, publication, providers, Optional.of(failure));
  }

  public enum Outcome {
    PUBLISHED,
    NOTHING_TO_PUBLISH,
    CEILING_EXCEEDED
  }
}
