package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.util.Optional;
import java.util.function.Supplier;

/** A provider whose download is whatever the test gives it; any other call fails the test. */
public final class FakeFlightProvider implements FlightProvider {

  private final Provider provider;
  private final Supplier<ProviderDownload> download;

  public FakeFlightProvider(Provider provider, Supplier<ProviderDownload> download) {
    this.provider = provider;
    this.download = download;
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
    throw new UnsupportedOperationException("This fake downloads only whole: " + origin.code());
  }

  @Override
  public Optional<Leg> quote(LegKey flight) {
    throw new UnsupportedOperationException("This fake quotes nothing: " + flight);
  }
}
