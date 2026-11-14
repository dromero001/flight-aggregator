package com.flightaggregator.architecture.fixture.millisread.domain;

public class Stamped {

  long stamp() {
    return System.currentTimeMillis();
  }
}
