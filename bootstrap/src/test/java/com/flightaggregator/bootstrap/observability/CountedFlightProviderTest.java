package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CountedFlightProviderTest {

  private static final LegKey FLIGHT =
      new LegKey(
          new Carrier("IB"),
          3170,
          new Airport("MAD"),
          new Airport("LHR"),
          Instant.parse("2026-11-20T07:15:00Z"));

  private final MeterRegistry meters = new SimpleMeterRegistry();

  /** Each call, under its operation and how it ended; the answer or the failure passes through. */
  @Test
  void countsEachOutcome() {
    ProviderDownload none = new ProviderDownload(List.of(), List.of());
    FlightProvider answering =
        counted(new Scripted(() -> none, origin -> none, flight -> Optional.empty()));
    FlightProvider failing =
        counted(
            new Scripted(
                () -> none,
                origin -> {
                  throw new ProviderUnavailableException(
                      Provider.ALPHA, FailureReason.TIMEOUT, null);
                },
                flight -> {
                  throw new RejectedExecutionException("Concurrency limit reached");
                }));

    assertThat(answering.download()).isSameAs(none);
    assertThat(answering.quote(FLIGHT)).isEmpty();
    assertThatThrownBy(() -> failing.downloadFrom(new Airport("MAD")))
        .isInstanceOf(ProviderUnavailableException.class);
    assertThatThrownBy(() -> failing.quote(FLIGHT)).isInstanceOf(RejectedExecutionException.class);

    assertThat(calls("download", "OK")).isEqualTo(1);
    assertThat(calls("quote", "NOT_SOLD")).isEqualTo(1);
    assertThat(calls("origin-download", "TIMEOUT")).isEqualTo(1);
    assertThat(calls("quote", "REJECTED")).isEqualTo(1);
  }

  /** Every call is counted (§6.10), one that fails in a way no reason names too. */
  @Test
  void countsAnyOtherFailureAsUnexpected() {
    FlightProvider broken =
        counted(
            new Scripted(
                () -> {
                  throw new IllegalStateException("A bug");
                },
                origin -> null,
                flight -> Optional.empty()));

    assertThatThrownBy(broken::download).isInstanceOf(IllegalStateException.class);

    assertThat(calls("download", "UNEXPECTED")).isEqualTo(1);
  }

  private FlightProvider counted(FlightProvider provider) {
    return new CountedFlightProvider(provider, meters);
  }

  private double calls(String operation, String outcome) {
    return meters
        .get("provider.calls")
        .tags("provider", "ALPHA", "operation", operation, "outcome", outcome)
        .counter()
        .count();
  }

  /** Alpha, answering each call with the test's function. */
  private record Scripted(
      Supplier<ProviderDownload> whole,
      Function<Airport, ProviderDownload> fromOrigin,
      Function<LegKey, Optional<Leg>> quotes)
      implements FlightProvider {

    @Override
    public Provider provider() {
      return Provider.ALPHA;
    }

    @Override
    public ProviderDownload download() {
      return whole.get();
    }

    @Override
    public ProviderDownload downloadFrom(Airport origin) {
      return fromOrigin.apply(origin);
    }

    @Override
    public Optional<Leg> quote(LegKey flight) {
      return quotes.apply(flight);
    }
  }
}
