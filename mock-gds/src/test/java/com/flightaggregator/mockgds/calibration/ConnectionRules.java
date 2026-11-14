package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.time.Duration;
import java.util.List;

/**
 * The connection rules of §4 of the design, restated for test-only calibration: how two legs
 * connect, and the no-revisit rule for a whole itinerary. The production rules will live in the
 * domain module; this copy only estimates how many itineraries the dataset yields.
 */
final class ConnectionRules {

  static final Duration MINIMUM_CONNECTION_TIME = Duration.ofMinutes(45);
  static final Duration MAXIMUM_LAYOVER = Duration.ofHours(24);

  private ConnectionRules() {}

  static Duration layover(Leg inbound, Leg outbound) {
    return Duration.between(inbound.arrival(), outbound.departure());
  }

  static boolean connects(Leg inbound, Leg outbound) {
    if (!inbound.destination().equals(outbound.origin())) {
      return false;
    }
    var layover = layover(inbound, outbound);
    return layover.compareTo(MINIMUM_CONNECTION_TIME) >= 0
        && layover.compareTo(MAXIMUM_LAYOVER) <= 0;
  }

  /**
   * Whether {@code next} connects to the last leg of {@code itinerary} without returning to an
   * airport on it.
   */
  static boolean extendsItinerary(List<Leg> itinerary, Leg next) {
    return connects(itinerary.getLast(), next) && !visits(itinerary, next.destination());
  }

  private static boolean visits(List<Leg> itinerary, String airport) {
    return itinerary.getFirst().origin().equals(airport)
        || itinerary.stream().anyMatch(leg -> leg.destination().equals(airport));
  }
}
