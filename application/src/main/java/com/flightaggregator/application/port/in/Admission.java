package com.flightaggregator.application.port.in;

import java.time.Duration;

/** Whether a client's request may go on (§6.6). */
public sealed interface Admission {

  /** Within the client's limit, or not counted because the counter is unavailable (§6.2). */
  record Admitted() implements Admission {}

  /** Past the client's limit; its window starts again after {@code retryAfter}. */
  record Rejected(Duration retryAfter) implements Admission {}
}
