package com.flightaggregator.architecture.fixture.clean.application.service;

import com.flightaggregator.architecture.fixture.clean.application.port.in.SearchFlights;
import com.flightaggregator.architecture.fixture.clean.application.port.out.FlightStore;
import com.flightaggregator.architecture.fixture.clean.domain.Flight;
import org.springframework.stereotype.Service;

@Service
public class FlightSearcher implements SearchFlights {
  private final FlightStore store;

  public FlightSearcher(FlightStore store) {
    this.store = store;
  }

  @Override
  public Flight search() {
    return store.load();
  }
}
