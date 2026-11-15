package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.domain.Airport;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Refresh coordination in memory: an origin is claimed once and its claim never expires, and a
 * refresh's time is remembered however old it gets.
 */
public final class InMemoryRefreshCoordination implements RefreshCoordination {

  private final Map<Airport, Instant> refreshed = new HashMap<>();
  private final Set<Airport> claimed = new HashSet<>();

  @Override
  public Optional<Instant> refreshedAt(Airport origin) {
    return Optional.ofNullable(refreshed.get(origin));
  }

  @Override
  public boolean claim(Airport origin) {
    return claimed.add(origin);
  }

  @Override
  public void refreshed(Airport origin, Instant at) {
    refreshed.put(origin, at);
  }

  /** Every origin claimed. */
  public Set<Airport> claimed() {
    return Set.copyOf(claimed);
  }
}
