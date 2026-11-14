package com.flightaggregator.mockgds.reference;

import java.util.List;

/** An airline of the mock network. Its business model decides which routes it flies. */
public sealed interface Airline permits NetworkCarrier, LowCostCarrier {

  /** Two-character IATA designator. */
  String code();

  /** Display name. */
  String name();

  /** IATA codes of the airports the airline flies from. */
  List<String> bases();
}
