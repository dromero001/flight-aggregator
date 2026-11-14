package com.flightaggregator.domain;

import java.time.Duration;
import java.time.Instant;

/** When two legs can be flown one after the other, and when a leg can still be sold. */
final class ConnectionRules {

  private static final Duration MINIMUM_CONNECTION_TIME = Duration.ofMinutes(45);
  private static final Duration MAXIMUM_LAYOVER = Duration.ofHours(24);

  private ConnectionRules() {}

  /** The earliest instant at which a leg connecting to the inbound one may depart. */
  static Instant earliestOnwardDeparture(Leg inbound) {
    return inbound.arrival().toInstant().plus(MINIMUM_CONNECTION_TIME);
  }

  /** The latest instant at which a leg connecting to the inbound one may depart. */
  static Instant latestOnwardDeparture(Leg inbound) {
    return inbound.arrival().toInstant().plus(MAXIMUM_LAYOVER);
  }

  static boolean connects(Leg inbound, Leg outbound) {
    Instant departure = outbound.departure().toInstant();
    return inbound.destination().equals(outbound.origin())
        && !departure.isBefore(earliestOnwardDeparture(inbound))
        && !departure.isAfter(latestOnwardDeparture(inbound));
  }

  static boolean isSellable(Leg leg, Instant now) {
    return leg.departure().toInstant().isAfter(now);
  }
}
