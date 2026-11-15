package com.flightaggregator.domain;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

/** The identity of a flight: the same key on two offers means the same flight. */
public record LegKey(
    Carrier carrier, int flightNumber, Airport origin, Airport destination, Instant departure) {

  public LegKey {
    requireNonNull(carrier, "carrier");
    requireNonNull(origin, "origin");
    requireNonNull(destination, "destination");
    requireNonNull(departure, "departure");
  }

  /** The canonical form, for example {@code IB3170 MAD-LHR 2026-11-20T07:15:00Z}. */
  @Override
  public String toString() {
    return carrier.code()
        + flightNumber
        + " "
        + origin.code()
        + "-"
        + destination.code()
        + " "
        + departure;
  }
}
