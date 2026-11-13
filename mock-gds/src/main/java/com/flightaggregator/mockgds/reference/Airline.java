package com.flightaggregator.mockgds.reference;

import java.util.Objects;
import java.util.Optional;

/**
 * An airline of the mock network.
 *
 * @param code    two-character IATA designator
 * @param name    display name
 * @param homeHub IATA code of the hub the airline flies from; empty for low-cost carriers without a hub
 */
public record Airline(String code, String name, Optional<String> homeHub) {

    public Airline {
        Objects.requireNonNull(code);
        Objects.requireNonNull(name);
        Objects.requireNonNull(homeHub);
    }

    static Airline hubCarrier(String code, String name, String homeHub) {
        return new Airline(code, name, Optional.of(homeHub));
    }

    static Airline lowCost(String code, String name) {
        return new Airline(code, name, Optional.empty());
    }
}
