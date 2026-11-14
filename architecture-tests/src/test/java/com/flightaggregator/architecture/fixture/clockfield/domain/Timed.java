package com.flightaggregator.architecture.fixture.clockfield.domain;

import java.time.Clock;

public class Timed {

  private final Clock clock;

  Timed(Clock clock) {
    this.clock = clock;
  }
}
