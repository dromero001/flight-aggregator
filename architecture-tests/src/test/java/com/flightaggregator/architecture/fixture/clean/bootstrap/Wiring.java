package com.flightaggregator.architecture.fixture.clean.bootstrap;

import com.flightaggregator.architecture.fixture.clean.adapter.persistence.Repository;
import com.flightaggregator.architecture.fixture.clean.adapter.web.Controller;
import com.flightaggregator.architecture.fixture.clean.application.service.FlightSearcher;

public class Wiring {
  public Controller controller() {
    return new Controller(new FlightSearcher(new Repository()));
  }
}
