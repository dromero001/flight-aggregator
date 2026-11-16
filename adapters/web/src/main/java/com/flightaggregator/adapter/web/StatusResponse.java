package com.flightaggregator.adapter.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flightaggregator.application.port.in.ProviderStatus;
import com.flightaggregator.application.port.in.ProviderStatus.Health;
import com.flightaggregator.application.port.in.StatusReport;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.domain.Provider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The provider-status screen (§6.8): the active generation, when it was published and when the next
 * run is due, whether a run is going on now, and one row per provider. Every field is always
 * written; a time not known yet, before the first run or for a provider with nothing stored, is
 * null, as the search's last cursor is.
 */
record StatusResponse(
    @Schema(requiredMode = REQUIRED) long generation,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"},
            format = "date-time")
        Instant publishedAt,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"},
            format = "date-time")
        Instant nextIngestionAt,
    @Schema(requiredMode = REQUIRED) boolean ingestionRunning,
    @Schema(requiredMode = REQUIRED) List<ProviderStatusResponse> providers) {

  static StatusResponse of(StatusReport report) {
    return new StatusResponse(
        report.generation(),
        report.publishedAt().orElse(null),
        report.nextIngestionAt().orElse(null),
        report.ingestionRunning(),
        report.providers().stream().map(ProviderStatusResponse::of).toList());
  }

  /**
   * One provider's row: its health and its breaker's state, the newest quote among its stored
   * offers, how many legs the last run used and the records it discarded by reason; {@code stale}
   * when the last run kept its offers from an earlier one.
   */
  record ProviderStatusResponse(
      @Schema(requiredMode = REQUIRED) Provider provider,
      @Schema(requiredMode = REQUIRED) Health health,
      @Schema(requiredMode = REQUIRED) BreakerState breaker,
      @Schema(
              requiredMode = REQUIRED,
              types = {"string", "null"},
              format = "date-time")
          Instant newestQuotedAt,
      @Schema(requiredMode = REQUIRED) int legs,
      @Schema(requiredMode = REQUIRED) Map<DiscardReason, Integer> discarded,
      @Schema(requiredMode = REQUIRED) boolean stale) {

    static ProviderStatusResponse of(ProviderStatus status) {
      return new ProviderStatusResponse(
          status.provider(),
          status.health(),
          status.breaker(),
          status.newestQuotedAt().orElse(null),
          status.legs(),
          status.discarded(),
          status.stale());
    }
  }
}
