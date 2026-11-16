package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.LatestRun;
import com.flightaggregator.application.port.out.ProviderRun;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The status store in memory: each provider's latest run, and every call to {@link #record} in
 * order, so that a test can tell what each run recorded.
 */
public final class InMemoryIngestionStatusStore implements IngestionStatusStore {

  /** One call to {@link #record}. */
  public record Recorded(List<ProviderRun> runs, Instant finishedAt) {}

  private final Map<Provider, ProviderRun> latest = new EnumMap<>(Provider.class);
  private final List<Recorded> recorded = new ArrayList<>();

  @Override
  public void record(List<ProviderRun> runs, Instant finishedAt) {
    recorded.add(new Recorded(List.copyOf(runs), finishedAt));
    runs.forEach(run -> latest.put(run.provider(), run));
  }

  @Override
  public Optional<LatestRun> latest() {
    return recorded.isEmpty()
        ? Optional.empty()
        : Optional.of(new LatestRun(recorded.getLast().finishedAt(), List.copyOf(latest.values())));
  }

  public List<Recorded> recorded() {
    return recorded;
  }
}
