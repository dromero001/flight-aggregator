package com.flightaggregator.architecture.fixture.shuffle.domain;

import java.util.Collections;
import java.util.List;

public class Offender {

  void mix(List<String> items) {
    Collections.shuffle(items);
  }
}
