package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A provider whose download, whose download of one origin, or whose quotes, are whatever the test
 * gives it; any other call fails the test.
 */
public final class FakeFlightProvider implements FlightProvider {

  private final Provider provider;
  private final Supplier<ProviderDownload> download;
  private final Function<Airport, ProviderDownload> originDownload;
  private final Function<LegKey, Optional<Leg>> quote;

  public FakeFlightProvider(Provider provider, Supplier<ProviderDownload> download) {
    this(
        provider,
        download,
        origin -> {
          throw new UnsupportedOperationException(
              "This fake downloads only whole: " + origin.code());
        },
        flight -> {
          throw new UnsupportedOperationException("This fake quotes nothing: " + flight);
        });
  }

  private FakeFlightProvider(
      Provider provider,
      Supplier<ProviderDownload> download,
      Function<Airport, ProviderDownload> originDownload,
      Function<LegKey, Optional<Leg>> quote) {
    this.provider = provider;
    this.download = download;
    this.originDownload = originDownload;
    this.quote = quote;
  }

  /** A provider that only quotes, each flight as the function answers it. */
  public static FakeFlightProvider quoting(
      Provider provider, Function<LegKey, Optional<Leg>> quote) {
    return new FakeFlightProvider(
        provider, FakeFlightProvider::onlyQuotes, origin -> onlyQuotes(), quote);
  }

  /** A provider that only downloads one origin at a time, each as the function answers it. */
  public static FakeFlightProvider downloadingFrom(
      Provider provider, Function<Airport, ProviderDownload> originDownload) {
    return new FakeFlightProvider(
        provider,
        () -> {
          throw new UnsupportedOperationException("This fake downloads only by origin");
        },
        originDownload,
        flight -> {
          throw new UnsupportedOperationException("This fake quotes nothing: " + flight);
        });
  }

  private static ProviderDownload onlyQuotes() {
    throw new UnsupportedOperationException("This fake only quotes");
  }

  @Override
  public Provider provider() {
    return provider;
  }

  @Override
  public ProviderDownload download() {
    return download.get();
  }

  @Override
  public ProviderDownload downloadFrom(Airport origin) {
    return originDownload.apply(origin);
  }

  @Override
  public Optional<Leg> quote(LegKey flight) {
    return quote.apply(flight);
  }
}
