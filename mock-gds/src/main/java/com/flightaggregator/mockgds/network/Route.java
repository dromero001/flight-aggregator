package com.flightaggregator.mockgds.network;

import com.flightaggregator.mockgds.reference.Airline;
import com.flightaggregator.mockgds.reference.Airport;
import com.flightaggregator.mockgds.reference.AirportRole;
import java.time.Duration;
import java.util.Objects;

/** A directed airport pair flown by one airline. */
public record Route(Airline airline, Airport origin, Airport destination) {

  private static final double CRUISE_SPEED_KM_PER_HOUR = 800.0;
  private static final long TAXI_AND_CLIMB_MINUTES = 30;
  private static final long ROUNDING_MINUTES = 5;

  public Route {
    Objects.requireNonNull(airline);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(destination);
    if (origin.equals(destination)) {
      throw new IllegalArgumentException("A route needs two different airports: " + origin.iata());
    }
  }

  /** Whether the route starts or ends at a hub. */
  public boolean servesHub() {
    return origin.role() == AirportRole.HUB || destination.role() == AirportRole.HUB;
  }

  public double distanceKm() {
    return origin.distanceKmTo(destination);
  }

  /**
   * Gate-to-gate time: cruise time at a constant speed plus a fixed allowance, rounded to five
   * minutes.
   */
  public Duration blockTime() {
    double minutes = distanceKm() / CRUISE_SPEED_KM_PER_HOUR * 60 + TAXI_AND_CLIMB_MINUTES;
    return Duration.ofMinutes(Math.round(minutes / ROUNDING_MINUTES) * ROUNDING_MINUTES);
  }

  @Override
  public String toString() {
    return airline.code() + " " + origin.iata() + "-" + destination.iata();
  }
}
