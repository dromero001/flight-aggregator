package com.flightaggregator.application.port.out;

import java.time.Instant;

/** The source of the current time, so that the application never reads the system clock. */
public interface Clock {

  Instant now();
}
