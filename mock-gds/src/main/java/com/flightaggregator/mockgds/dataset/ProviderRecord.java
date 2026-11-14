package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;

/**
 * One record of a provider's inventory, as its serializer will write it: a valid leg or a malformed
 * one.
 */
public sealed interface ProviderRecord permits ValidLeg, MalformedLeg {

  /** The leg the record is about; for a malformed record, the leg before it was broken. */
  Leg leg();

  default Provider provider() {
    return leg().provider();
  }
}
