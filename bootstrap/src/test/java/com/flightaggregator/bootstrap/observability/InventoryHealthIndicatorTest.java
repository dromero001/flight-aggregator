package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.ProviderStatus;
import com.flightaggregator.application.port.in.StatusReport;
import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class InventoryHealthIndicatorTest {

  private static final Instant PUBLISHED_AT = Instant.parse("2026-11-20T08:01:00Z");

  /** Q8-A: every provider up and Valkey answering is a service up. */
  @Test
  void isUpWithEveryProviderFresh() {
    InventoryHealthIndicator indicator =
        new InventoryHealthIndicator(
            () -> report(provider(Provider.ALPHA, ProviderStatus.Health.UP)), () -> true);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  /**
   * §6.5: serving a provider's kept offers is degraded service, not a broken one, so the status is
   * DEGRADED, which Boot answers with 200 (only DOWN and OUT_OF_SERVICE are 503), and the
   * orchestrator does not restart the API. The details, shown to the admin only, name each
   * provider's health.
   */
  @Test
  void isDegradedWith200WhenAProviderIsKept() {
    InventoryHealthIndicator indicator =
        new InventoryHealthIndicator(
            () ->
                report(
                    provider(Provider.ALPHA, ProviderStatus.Health.UP),
                    provider(Provider.BETA, ProviderStatus.Health.DOWN)),
            () -> true);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(InventoryHealthIndicator.DEGRADED);
    assertThat(HttpCodeStatusMapper.getDefault().getStatusCode(health.getStatus())).isEqualTo(200);
    assertThat(health.getDetails())
        .containsEntry("providers", Map.of("ALPHA", "UP", "BETA", "DOWN"));
  }

  /** §6.2: without Valkey, search is only slower, so the service is degraded, never down. */
  @Test
  void isDegradedWhenValkeyIsDown() {
    InventoryHealthIndicator indicator =
        new InventoryHealthIndicator(
            () -> report(provider(Provider.ALPHA, ProviderStatus.Health.UP)), () -> false);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(InventoryHealthIndicator.DEGRADED);
    assertThat(health.getDetails()).containsEntry("valkey", "DOWN");
  }

  /**
   * Q8-A: PostgreSQL out of reach is the one cause of DOWN. The status cannot be read then, and the
   * failure is reported as DOWN with its cause, which Boot answers with 503, never escaping as a
   * 500 from the health endpoint.
   */
  @Test
  void isDownWhenTheInventoryCannotBeRead() {
    IllegalStateException unreachable = new IllegalStateException("PostgreSQL is unreachable");
    InventoryHealthIndicator indicator =
        new InventoryHealthIndicator(
            () -> {
              throw unreachable;
            },
            () -> true);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(HttpCodeStatusMapper.getDefault().getStatusCode(health.getStatus())).isEqualTo(503);
    assertThat(health.getDetails())
        .containsEntry("error", "java.lang.IllegalStateException: PostgreSQL is unreachable");
  }

  private static StatusReport report(ProviderStatus... providers) {
    return new StatusReport(
        7,
        Optional.of(PUBLISHED_AT),
        Optional.of(PUBLISHED_AT.plusSeconds(1800)),
        List.of(providers));
  }

  private static ProviderStatus provider(Provider provider, ProviderStatus.Health health) {
    return new ProviderStatus(
        provider,
        health,
        BreakerState.CLOSED,
        Optional.of(PUBLISHED_AT),
        100,
        Map.of(),
        health == ProviderStatus.Health.DOWN);
  }
}
