package com.flightaggregator.architecture.fixture.randomread.domain;

import java.util.UUID;

public class Identified {

  UUID fresh() {
    return UUID.randomUUID();
  }
}
