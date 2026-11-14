package com.flightaggregator.architecture.fixture.localnow.domain;

import java.time.LocalDateTime;

public class Offender {

  LocalDateTime when() {
    return LocalDateTime.now();
  }
}
