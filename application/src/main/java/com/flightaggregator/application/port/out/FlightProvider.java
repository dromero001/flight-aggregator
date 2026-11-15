package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Provider;

/** One flight provider, downloaded whole. */
public interface FlightProvider {

  Provider provider();

  /**
   * Returns only when every page arrived.
   *
   * @throws ProviderUnavailableException when any page did not
   */
  ProviderDownload download();
}
