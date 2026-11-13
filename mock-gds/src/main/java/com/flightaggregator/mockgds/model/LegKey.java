package com.flightaggregator.mockgds.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Identity of a leg across providers (§4): airline, flight number, origin, destination and departure instant.
 * Two providers selling the same flight produce legs with the same key.
 */
public record LegKey(String airline, int flightNumber, String origin, String destination, Instant departure) {

    public LegKey {
        Objects.requireNonNull(airline);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(departure);
    }
}
