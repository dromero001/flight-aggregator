package com.flightaggregator.mockgds.reference;

import java.util.List;
import java.util.Objects;

/**
 * A network carrier: it feeds one hub, flying from it to every other hub and to the spokes and
 * long-haul airports that the hub gateways.
 *
 * @param hub IATA code of its hub
 */
public record NetworkCarrier(String code, String name, String hub) implements Airline {

  public NetworkCarrier {
    Objects.requireNonNull(code);
    Objects.requireNonNull(name);
    Objects.requireNonNull(hub);
  }

  @Override
  public List<String> bases() {
    return List.of(hub);
  }
}
