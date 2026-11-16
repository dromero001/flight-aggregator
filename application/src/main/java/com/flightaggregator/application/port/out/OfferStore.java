package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.util.List;

/** The last complete download of each provider. */
public interface OfferStore {

  /** Replaces every stored offer of the provider by these ones. */
  void replace(Provider provider, List<Leg> offers);

  /**
   * Replaces the provider's stored offers departing from the origin by these ones, after its other
   * offers; its offers from every other origin do not change.
   */
  void replaceFrom(Provider provider, Airport origin, List<Leg> offers);

  /** The stored offers of the provider, in the page order they were stored. */
  List<Leg> offers(Provider provider);

  /** Every provider's stored offer of the flight, the cheapest first. */
  List<Leg> offersOf(LegKey flight);
}
