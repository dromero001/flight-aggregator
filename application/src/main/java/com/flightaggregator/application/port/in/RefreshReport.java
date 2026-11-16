package com.flightaggregator.application.port.in;

import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Provider;
import java.util.Map;
import java.util.Optional;

/**
 * What one refresh of an origin did. Each provider is {@code FRESH} when its download of the origin
 * arrived with usable legs, and {@code KEPT}, with the reason its download failed, when its stored
 * offers of the origin were used again. A {@code REFRESHED} origin's itineraries were rewritten
 * into {@code generation}, {@code itineraries} of them; a refresh with {@code NOTHING_FRESH} or
 * {@code CEILING_EXCEEDED} (more itineraries through the origin than the run's ceiling, R7) wrote
 * nothing, and both are 0.
 */
public record RefreshReport(
    Airport origin,
    Outcome outcome,
    long generation,
    long itineraries,
    Map<Provider, ProviderRefresh> providers) {

  public RefreshReport {
    providers = Map.copyOf(providers);
  }

  public enum Outcome {
    REFRESHED,
    NOTHING_FRESH,
    CEILING_EXCEEDED
  }

  /**
   * What the refresh did with one provider: {@code failure} is why its download failed, as a run's
   * {@link ProviderReport} names it, and its status follows from it.
   */
  public record ProviderRefresh(Optional<FailureReason> failure) {

    /** A provider whose download of the origin arrived with usable legs. */
    public static final ProviderRefresh FRESH = new ProviderRefresh(Optional.empty());

    /**
     * A provider whose stored offers were used again, its download having failed for {@code
     * reason}.
     */
    public static ProviderRefresh kept(FailureReason reason) {
      return new ProviderRefresh(Optional.of(reason));
    }

    /** {@code KEPT} exactly when the download failed. */
    public ProviderReport.Status status() {
      return ProviderReport.Status.of(failure);
    }
  }
}
