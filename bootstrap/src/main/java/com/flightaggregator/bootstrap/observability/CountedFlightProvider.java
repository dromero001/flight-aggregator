package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Counts every call of one provider as {@code provider.calls{provider, operation, outcome}}
 * (§6.10): {@code operation} is {@code download}, {@code origin-download} or {@code quote}, and
 * {@code outcome} is {@code OK}, {@code NOT_SOLD} for a quote of a flight no longer sold, the
 * {@code FailureReason} of a provider that failed, or {@code REJECTED} for a call over the
 * provider's concurrency limit; a call that fails any other way is {@code UNEXPECTED}, so every
 * call is counted. The answer or the failure passes through unchanged.
 */
public final class CountedFlightProvider implements FlightProvider {

  private static final String OK = "OK";
  private static final String NOT_SOLD = "NOT_SOLD";
  private static final String REJECTED = "REJECTED";

  private final FlightProvider provider;
  private final MeterRegistry meters;

  public CountedFlightProvider(FlightProvider provider, MeterRegistry meters) {
    this.provider = provider;
    this.meters = meters;
  }

  @Override
  public Provider provider() {
    return provider.provider();
  }

  /** The provider it counts, so a log line names it. */
  @Override
  public String toString() {
    return provider.toString();
  }

  @Override
  public ProviderDownload download() {
    return counted("download", provider::download, download -> OK);
  }

  @Override
  public ProviderDownload downloadFrom(Airport origin) {
    return counted("origin-download", () -> provider.downloadFrom(origin), download -> OK);
  }

  @Override
  public Optional<Leg> quote(LegKey flight) {
    return counted(
        "quote", () -> provider.quote(flight), quote -> quote.isPresent() ? OK : NOT_SOLD);
  }

  private <T> T counted(String operation, Supplier<T> call, Function<T, String> outcome) {
    T answer;
    try {
      answer = call.get();
    } catch (ProviderUnavailableException e) {
      count(operation, e.reason().name());
      throw e;
    } catch (RejectedExecutionException e) {
      count(operation, REJECTED);
      throw e;
    } catch (RuntimeException e) {
      count(operation, FailureReason.UNEXPECTED.name());
      throw e;
    }
    count(operation, outcome.apply(answer));
    return answer;
  }

  private void count(String operation, String outcome) {
    meters
        .counter(
            "provider.calls",
            "provider",
            provider.provider().name(),
            "operation",
            operation,
            "outcome",
            outcome)
        .increment();
  }
}
