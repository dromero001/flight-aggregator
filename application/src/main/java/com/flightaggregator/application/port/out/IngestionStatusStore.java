package com.flightaggregator.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Each provider's status as the last run left it (Q7-B), kept where every instance reads it and a
 * restart does not lose it, for the provider-status screen (§6.8).
 */
public interface IngestionStatusStore {

  /** Records a run's providers, each replacing what an earlier run recorded for it, at once. */
  void record(List<ProviderRun> runs, Instant finishedAt);

  /** The latest run recorded, its end and its providers read together; empty before the first. */
  Optional<LatestRun> latest();
}
