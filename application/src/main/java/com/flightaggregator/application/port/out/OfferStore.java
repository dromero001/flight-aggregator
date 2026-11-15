package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.util.List;

/** The last complete download of each provider. */
public interface OfferStore {

  /** Replaces every stored offer of the provider by these ones. */
  void replace(Provider provider, List<Leg> offers);

  /** The stored offers of the provider, in the page order they were stored. */
  List<Leg> offers(Provider provider);
}
