package com.flightaggregator.architecture.fixture.clean.adapter.web;

import com.flightaggregator.architecture.fixture.clean.application.port.in.SearchFlights;

public class Controller {
  private final SearchFlights search;

  public Controller(SearchFlights search) {
    this.search = search;
  }

  public String handle() {
    return search.search().id();
  }
}
