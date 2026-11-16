package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.application.port.in.InventoryStatus;
import com.flightaggregator.application.port.in.ProviderStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The service's health as Q8-A puts it (§6.5): {@code DEGRADED} while any provider is not up (its
 * offers kept from an earlier run, or its breaker open or half-open) or Valkey does not answer its
 * ping (§6.2), otherwise {@code UP}. {@code DEGRADED} still answers 200, so the orchestrator does
 * not restart an API that searches; only PostgreSQL can make the service {@code DOWN}: Boot's own
 * {@code db} indicator, and this one when it cannot read the status from it, which Boot's {@link
 * AbstractHealthIndicator} reports as {@code DOWN} (503) with the exception, logged at WARN. {@code
 * management.endpoint.health.status.order} places {@code DEGRADED} between {@code DOWN} and {@code
 * UP}. The details, which only the admin sees, give each provider's health and Valkey's.
 */
public final class InventoryHealthIndicator extends AbstractHealthIndicator {

  public static final Status DEGRADED = new Status("DEGRADED");

  private final InventoryStatus inventory;
  private final BooleanSupplier valkeyAnswers;

  public InventoryHealthIndicator(InventoryStatus inventory, BooleanSupplier valkeyAnswers) {
    super("The inventory status could not be read for the health check");
    this.inventory = inventory;
    this.valkeyAnswers = valkeyAnswers;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) {
    List<ProviderStatus> providers = inventory.status().providers();
    boolean valkeyUp = valkeyAnswers.getAsBoolean();
    boolean degraded =
        !valkeyUp
            || providers.stream()
                .anyMatch(provider -> provider.health() != ProviderStatus.Health.UP);
    Map<String, String> health = new LinkedHashMap<>();
    providers.forEach(provider -> health.put(provider.provider().name(), provider.health().name()));
    builder
        .status(degraded ? DEGRADED : Status.UP)
        .withDetail("providers", health)
        .withDetail("valkey", valkeyUp ? "UP" : "DOWN");
  }
}
