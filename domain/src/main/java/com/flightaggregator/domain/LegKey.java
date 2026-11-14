package com.flightaggregator.domain;

import java.time.Instant;

/** The identity of a flight: the same key on two offers means the same flight. */
public record LegKey(
    Carrier carrier, int flightNumber, Airport origin, Airport destination, Instant departure) {

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
