package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.model.Leg;
import java.util.Objects;

/** A record that the provider serves correctly. */
public record ValidLeg(Leg leg) implements ProviderRecord {

    public ValidLeg {
        Objects.requireNonNull(leg);
    }
}
