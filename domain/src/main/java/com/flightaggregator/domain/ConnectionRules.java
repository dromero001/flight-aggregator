package com.flightaggregator.domain;

import java.time.Duration;
import java.time.Instant;

/** When two legs can be flown one after the other, and when a leg can still be sold. */
final class ConnectionRules {

  static final Duration MINIMUM_CONNECTION_TIME = Duration.ofMinutes(45);
  static final Duration MAXIMUM_LAYOVER = Duration.ofHours(24);

  private ConnectionRules() {}

  /** The time between the inbound arrival and the outbound departure, measured in instants. */
  static Duration layover(Leg inbound, Leg outbound) {
    return Duration.between(inbound.arrival().toInstant(), outbound.departure().toInstant());
  }

  static boolean connects(Leg inbound, Leg outbound) {
    Duration layover = layover(inbound, outbound);
    return inbound.destination().equals(outbound.origin())
        && layover.compareTo(MINIMUM_CONNECTION_TIME) >= 0
        && layover.compareTo(MAXIMUM_LAYOVER) <= 0;
  }

  static boolean isSellable(Leg leg, Instant now) {
    return leg.departure().toInstant().isAfter(now);
  }
}
