package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

  /**
   * A quote that fails in a way no reason names is a bug that the re-price treats as the provider
   * failing (§6.5): besides its count, it is one ERROR line naming the provider, with its stack
   * trace, since nothing else reports it.
   */
  @Test
  void logsAQuoteThatFailsUnexpectedlyAtErrorWithItsStackTrace() {
    IllegalStateException bug = new IllegalStateException("A bug in the quote");
    FlightProvider broken =
        counted(
            new Scripted(
                () -> null,
                origin -> null,
                flight -> {
                  throw bug;
                }));
    Logger logger = (Logger) LoggerFactory.getLogger(CountedFlightProvider.class);
    ListAppender<ILoggingEvent> log = new ListAppender<>();
    log.start();
    logger.addAppender(log);
    try {
      assertThatThrownBy(() -> broken.quote(FLIGHT)).isSameAs(bug);
    } finally {
      logger.detachAppender(log);
    }

    assertThat(log.list)
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.getLevel()).isEqualTo(Level.ERROR);
              assertThat(line.getFormattedMessage())
                  .isEqualTo("A provider's quote failed unexpectedly");
              assertThat(line.getKeyValuePairs())
                  .extracting(pair -> pair.key + "=" + pair.value)
                  .containsExactly("provider=ALPHA");
              assertThat(((ThrowableProxy) line.getThrowableProxy()).getThrowable()).isSameAs(bug);
            });
    assertThat(calls("quote", "UNEXPECTED")).isEqualTo(1);
  }

  /**
   * The provider is named once, when the counter is built: neither {@code provider()} nor the count
   * of a refused call asks the delegate again, so a limit that also refused {@code provider()} (an
   * interface proxy loses its exemptions) could not stop a call from being counted (plan 5 Task 2,
   * C2).
   */
  @Test
  void namesItsProviderWithoutAskingTheDelegateAgain() {
    FlightProvider limited =
        new NamedOnce(
            new Scripted(
                () -> null,
                origin -> null,
                flight -> {
                  throw new RejectedExecutionException("Concurrency limit reached");
                }));
    FlightProvider counted = counted(limited);

    assertThat(counted.provider()).isEqualTo(Provider.ALPHA);
    assertThatThrownBy(() -> counted.quote(FLIGHT)).isInstanceOf(RejectedExecutionException.class);
    assertThat(calls("quote", "REJECTED")).isEqualTo(1);
  }

  /**
   * Every operation and outcome of the provider is counted from zero, registered when it is
   * wrapped, so Prometheus's {@code rate} sees the startup run's calls (E-T6-1, built on
   * 2026-11-16): each operation ends {@code OK} or with any {@code FailureReason}, and a quote also
   * {@code NOT_SOLD}.
   */
  @Test
  void registersEveryOperationAndOutcomeAtZeroBeforeTheFirstCall() {
    ProviderDownload none = new ProviderDownload(List.of(), List.of());
    counted(new Scripted(() -> none, origin -> none, flight -> Optional.empty()));

    List<Tuple> expected = new ArrayList<>();
    for (String operation : List.of("download", "origin-download", "quote")) {
      expected.add(tuple("ALPHA", operation, "OK", 0.0));
      for (FailureReason reason : FailureReason.values()) {
        expected.add(tuple("ALPHA", operation, reason.name(), 0.0));
      }
    }
    expected.add(tuple("ALPHA", "quote", "NOT_SOLD", 0.0));
    assertThat(meters.find("provider.calls").counters())
        .extracting(
            counter -> counter.getId().getTag("provider"),
            counter -> counter.getId().getTag("operation"),
            counter -> counter.getId().getTag("outcome"),
            Counter::count)
        .containsExactlyInAnyOrderElementsOf(expected);
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

  /** A provider that names itself once, then refuses {@code provider()} as an exhausted limit. */
  private static final class NamedOnce implements FlightProvider {

    private final FlightProvider delegate;
    private boolean named;

    NamedOnce(FlightProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Provider provider() {
      if (named) {
        throw new RejectedExecutionException("Concurrency limit reached");
      }
      named = true;
      return delegate.provider();
    }

    @Override
    public ProviderDownload download() {
      return delegate.download();
    }

    @Override
    public ProviderDownload downloadFrom(Airport origin) {
      return delegate.downloadFrom(origin);
    }

    @Override
    public Optional<Leg> quote(LegKey flight) {
      return delegate.quote(flight);
    }
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
