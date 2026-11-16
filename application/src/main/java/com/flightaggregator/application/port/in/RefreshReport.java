package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Provider;
import java.util.Map;

/**
 * What one refresh of an origin did. Each provider is {@code FRESH} when its download of the origin
 * arrived with usable legs, and {@code KEPT} when its stored offers of the origin were used again.
 * A {@code REFRESHED} origin's itineraries were rewritten into {@code generation}, {@code
 * itineraries} of them; a refresh with {@code NOTHING_FRESH} or {@code CEILING_EXCEEDED} (more
 * itineraries through the origin than the run's ceiling, R7) wrote nothing, and both are 0.
 */
public record RefreshReport(
    Airport origin,
    Outcome outcome,
    long generation,
    long itineraries,
    Map<Provider, ProviderReport.Status> providers) {

  public RefreshReport {
    providers = Map.copyOf(providers);
  }

  public enum Outcome {
    REFRESHED,
    NOTHING_FRESH,
    CEILING_EXCEEDED
  }
}
