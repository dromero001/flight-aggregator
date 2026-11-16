package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.InventoryStatus;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.in.ProviderStatus;
import com.flightaggregator.application.port.in.ProviderStatus.Health;
import com.flightaggregator.application.port.in.StatusReport;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.LatestRun;
import com.flightaggregator.application.port.out.ProviderBreakers;
import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.application.port.out.ProviderRun;
import java.time.Duration;
import java.util.Optional;

/**
 * The provider-status screen's data (§6.8): the active generation from the inventory, each
 * provider's last run from the status store, which every instance shares (Q7-B), and its breaker's
 * state on this instance, read live.
 */
public final class InventoryStatusService implements InventoryStatus {

  private final IngestionStatusStore statuses;
  private final ItineraryReader inventory;
  private final ProviderBreakers breakers;
  private final Duration ingestionInterval;

  public InventoryStatusService(
      IngestionStatusStore statuses,
      ItineraryReader inventory,
      ProviderBreakers breakers,
      Duration ingestionInterval) {
    this.statuses = statuses;
    this.inventory = inventory;
    this.breakers = breakers;
    this.ingestionInterval = ingestionInterval;
  }

  @Override
  public StatusReport status() {
    InventoryState state = inventory.state();
    Optional<LatestRun> run = statuses.latest();
    return new StatusReport(
        state.generation(),
        state.publishedAt(),
        run.map(latest -> latest.finishedAt().plus(ingestionInterval)),
        run.stream().flatMap(latest -> latest.providers().stream()).map(this::status).toList());
  }

  private ProviderStatus status(ProviderRun run) {
    BreakerState breaker = breakers.state(run.provider());
    boolean stale = run.status() == ProviderReport.Status.KEPT;
    return new ProviderStatus(
        run.provider(),
        health(stale, breaker),
        breaker,
        run.newestQuotedAt(),
        run.offersUsed(),
        run.discarded(),
        stale);
  }

  /** Q8-A. */
  private static Health health(boolean stale, BreakerState breaker) {
    if (stale || breaker == BreakerState.OPEN) {
      return Health.DOWN;
    }
    return breaker == BreakerState.HALF_OPEN ? Health.DEGRADED : Health.UP;
  }
}
