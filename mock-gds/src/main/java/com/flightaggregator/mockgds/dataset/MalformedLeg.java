package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.model.Leg;
import java.util.Objects;

/** A record that the provider serves broken: {@code source} rendered with {@code defect}. */
public record MalformedLeg(Leg leg, Defect defect) implements ProviderRecord {

  public MalformedLeg {
    Objects.requireNonNull(leg);
    Objects.requireNonNull(defect);
  }
}
