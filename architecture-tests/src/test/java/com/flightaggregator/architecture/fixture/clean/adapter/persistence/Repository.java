package com.flightaggregator.architecture.fixture.clean.adapter.persistence;

import com.flightaggregator.architecture.fixture.clean.application.port.out.FlightStore;
import com.flightaggregator.architecture.fixture.clean.domain.Flight;
import com.flightaggregator.architecture.fixture.clean.domain.Leg;

public class Repository implements FlightStore {
  @Override
  public Flight load() {
    return new Flight("f", new Leg("MAD"));
  }
}
