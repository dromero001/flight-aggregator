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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts every call of one provider as {@code provider.calls{provider, operation, outcome}}
 * (§6.10): {@code operation} is {@code download}, {@code origin-download} or {@code quote}, and
 * {@code outcome} is {@code OK}, {@code NOT_SOLD} for a quote of a flight no longer sold, the
 * {@code FailureReason} of a provider that failed, or {@code REJECTED} for a call over the
 * provider's concurrency limit; a call that fails any other way is {@code UNEXPECTED}, so every
 * call is counted. The answer or the failure passes through unchanged.
 *
 * <p>An {@code UNEXPECTED} quote is also one {@code ERROR} line with its stack trace: it is a bug,
 * and the re-price that asked treats it as the provider failing (§6.5), so nothing else reports it.
 * A run's download failure is reported by the run, which logs each kept provider's with its stack
 * trace. A refresh's ({@code origin-download}) is only counted here: the refresh's report line
 * names the provider {@code KEPT}, with no reason and no stack trace.
 */
public final class CountedFlightProvider implements FlightProvider {

  private static final Logger LOG = LoggerFactory.getLogger(CountedFlightProvider.class);

  private static final String OK = "OK";
  private static final String NOT_SOLD = "NOT_SOLD";

  private final FlightProvider provider;

  /**
   * The provider's name, asked once: neither {@link #provider()} nor a count asks the limited
   * delegate again, so the counting path makes no call of it besides the counted one.
   */
  private final Provider name;

  private final MeterRegistry meters;

  public CountedFlightProvider(FlightProvider provider, MeterRegistry meters) {
    this.provider = provider;
    this.name = provider.provider();
    this.meters = meters;
  }

  @Override
  public Provider provider() {
    return name;
  }

  /** The provider it counts, so a log line names it. */
  @Override
  public String toString() {
    return provider.toString();
  }

  @Override
  public ProviderDownload download() {
    return counted("download", provider::download, download -> OK, unexpected -> {});
  }

  @Override
  public ProviderDownload downloadFrom(Airport origin) {
    return counted(
        "origin-download", () -> provider.downloadFrom(origin), download -> OK, unexpected -> {});
  }

  @Override
  public Optional<Leg> quote(LegKey flight) {
    return counted(
        "quote",
        () -> provider.quote(flight),
        quote -> quote.isPresent() ? OK : NOT_SOLD,
        unexpected ->
            LOG.atError()
                .addKeyValue("provider", name.name())
                .setCause(unexpected)
                .log("A provider's quote failed unexpectedly"));
  }

  /** Counts the call; one that fails {@code UNEXPECTED} is handed to {@code unexpected} first. */
  private <T> T counted(
      String operation,
      Supplier<T> call,
      Function<T, String> outcome,
      Consumer<RuntimeException> unexpected) {
    T answer;
    try {
      answer = call.get();
    } catch (ProviderUnavailableException e) {
      count(operation, e.reason().name());
      throw e;
    } catch (RejectedExecutionException e) {
      count(operation, FailureReason.REJECTED.name());
      throw e;
    } catch (RuntimeException e) {
      count(operation, FailureReason.UNEXPECTED.name());
      unexpected.accept(e);
      throw e;
    }
    count(operation, outcome.apply(answer));
    return answer;
  }

  private void count(String operation, String outcome) {
    meters
        .counter(
            "provider.calls", "provider", name.name(), "operation", operation, "outcome", outcome)
        .increment();
  }
}
