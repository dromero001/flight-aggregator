package com.flightaggregator.application.service;

import static com.flightaggregator.application.port.in.ProviderStatus.Health.DEGRADED;
import static com.flightaggregator.application.port.in.ProviderStatus.Health.DOWN;
import static com.flightaggregator.application.port.in.ProviderStatus.Health.UP;
import static com.flightaggregator.application.port.out.ProviderBreakers.BreakerState.CLOSED;
import static com.flightaggregator.application.port.out.ProviderBreakers.BreakerState.HALF_OPEN;
import static com.flightaggregator.application.port.out.ProviderBreakers.BreakerState.OPEN;
import static com.flightaggregator.domain.Provider.ALPHA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.flightaggregator.application.port.in.ProviderStatus;
import com.flightaggregator.application.port.in.ProviderStatus.Health;
import com.flightaggregator.application.port.in.StatusReport;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.application.port.out.ProviderRun;
import com.flightaggregator.application.service.fakes.InMemoryIngestionStatusStore;
import com.flightaggregator.application.service.fakes.ScriptedItineraryReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InventoryStatusServiceTest {

  private static final Instant PUBLISHED_AT = Instant.parse("2026-11-20T08:01:00Z");
  private static final Instant RUN_END = Instant.parse("2026-11-20T08:01:30Z");
  private static final Instant NEWEST_QUOTE = Instant.parse("2026-11-20T08:00:40Z");
  private static final Duration INTERVAL = Duration.ofMinutes(30);

  private final InMemoryIngestionStatusStore statuses = new InMemoryIngestionStatusStore();

  static Stream<Arguments> healthByLastRunAndBreaker() {
    Optional<FailureReason> fresh = Optional.empty();
    Optional<FailureReason> kept = Optional.of(FailureReason.TIMEOUT);
    return Stream.of(
        argumentSet("fresh, breaker closed", fresh, CLOSED, UP, false),
        argumentSet("fresh, breaker half-open", fresh, HALF_OPEN, DEGRADED, false),
        argumentSet("fresh, breaker open", fresh, OPEN, DOWN, false),
        argumentSet("kept, breaker closed", kept, CLOSED, DOWN, true),
        argumentSet("kept, breaker half-open", kept, HALF_OPEN, DOWN, true));
  }

  /**
   * Q8-A: a provider whose last run kept its offers, or whose breaker is open, is down; one whose
   * breaker is half-open is degraded; a kept one is stale. The rest of its row is its last run's.
   */
  @ParameterizedTest
  @MethodSource("healthByLastRunAndBreaker")
  void reportsEachProvidersHealthAndStaleness(
      Optional<FailureReason> failure, BreakerState breaker, Health health, boolean stale) {
    statuses.record(
        List.of(
            new ProviderRun(
                ALPHA, failure, 120, Map.of(DiscardReason.PRICE, 2), Optional.of(NEWEST_QUOTE))),
        RUN_END);
    InventoryStatusService service =
        new InventoryStatusService(
            statuses, new ScriptedItineraryReader(7, PUBLISHED_AT), provider -> breaker, INTERVAL);

    StatusReport report = service.status();

    assertThat(report.generation()).isEqualTo(7);
    assertThat(report.publishedAt()).contains(PUBLISHED_AT);
    assertThat(report.providers())
        .containsExactly(
            new ProviderStatus(
                ALPHA,
                health,
                breaker,
                Optional.of(NEWEST_QUOTE),
                120,
                Map.of(DiscardReason.PRICE, 2),
                stale));
  }

  /** §6.4: the next run starts the interval after the last one ended. */
  @Test
  void computesTheNextIngestion() {
    statuses.record(
        List.of(new ProviderRun(ALPHA, Optional.empty(), 120, Map.of(), Optional.empty())),
        RUN_END);
    InventoryStatusService service =
        new InventoryStatusService(
            statuses, new ScriptedItineraryReader(7, PUBLISHED_AT), provider -> CLOSED, INTERVAL);

    assertThat(service.status().nextIngestionAt()).contains(RUN_END.plus(INTERVAL));
  }

  /**
   * Before the first run ends there is generation 0, nothing published, no run to count the next
   * one from and no provider to report.
   */
  @Test
  void reportsNothingBeforeTheFirstRun() {
    InventoryStatusService service =
        new InventoryStatusService(
            statuses,
            new ScriptedItineraryReader(7, PUBLISHED_AT).beforeTheFirstPublication(),
            provider -> CLOSED,
            INTERVAL);

    assertThat(service.status())
        .isEqualTo(new StatusReport(0, Optional.empty(), Optional.empty(), List.of()));
  }
}
