package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.Airport;

/** The stale-route refresh of one origin (§4.1, §6.4). */
public interface RefreshStaleRoute {

  /**
   * Downloads the origin's legs from every provider again and rewrites, in the published inventory,
   * every itinerary with a leg departing from it.
   */
  RefreshReport refresh(Airport origin);
}
