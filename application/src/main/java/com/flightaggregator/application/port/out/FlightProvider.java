package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.util.Optional;

/** One flight provider: downloaded whole or one origin at a time, or asked for one flight. */
public interface FlightProvider {

  Provider provider();

  /**
   * Returns only when every page arrived.
   *
   * @throws ProviderUnavailableException when any page did not
   */
  ProviderDownload download();

  /**
   * The legs departing from one origin, whole as {@link #download()}.
   *
   * @throws ProviderUnavailableException when any page did not arrive
   */
  ProviderDownload downloadFrom(Airport origin);

  /**
   * The provider's offer of one flight now: empty when it no longer sells it.
   *
   * @throws ProviderUnavailableException when the quote did not arrive, or did not answer the
   *     flight asked for with one valid record ({@code MALFORMED_PAGE})
   */
  Optional<Leg> quote(LegKey flight);
}
