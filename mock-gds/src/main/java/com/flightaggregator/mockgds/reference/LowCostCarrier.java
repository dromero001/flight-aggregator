package com.flightaggregator.mockgds.reference;

import java.util.List;
import java.util.Objects;

/**
 * A low-cost carrier: it flies point-to-point between every pair of its bases.
 *
 * @param bases IATA codes of its bases
 */
public record LowCostCarrier(String code, String name, List<String> bases) implements Airline {

    public LowCostCarrier {
        Objects.requireNonNull(code);
        Objects.requireNonNull(name);
        bases = List.copyOf(bases);
        if (bases.size() < 2) {
            throw new IllegalArgumentException(code + " needs at least two bases to fly point-to-point: " + bases);
        }
    }
}
