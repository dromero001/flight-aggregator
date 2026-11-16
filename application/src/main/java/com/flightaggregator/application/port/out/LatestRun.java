package com.flightaggregator.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * The latest run the status store holds, read at once: when it ended, and what it did with each
 * provider, in {@code Provider} order, so that the screen never shows one run's providers with
 * another run's time (Q7-B).
 */
public record LatestRun(Instant finishedAt, List<ProviderRun> providers) {

  public LatestRun {
    providers = List.copyOf(providers);
  }
}
