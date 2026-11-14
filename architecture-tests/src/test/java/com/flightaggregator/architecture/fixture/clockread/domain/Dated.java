package com.flightaggregator.architecture.fixture.clockread.domain;

import java.time.Instant;

public class Dated {

  Instant today() {
    return Instant.now();
  }
}
