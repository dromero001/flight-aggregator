package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.out.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The application's clock: the system's. */
@Component
final class SystemClock implements Clock {

  @Override
  public Instant now() {
    return Instant.now();
  }
}
