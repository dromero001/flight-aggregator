package com.flightaggregator.architecture.fixture.clean.application.port.out;

import com.flightaggregator.architecture.fixture.clean.domain.Flight;

public interface FlightStore {
  Flight load();
}
