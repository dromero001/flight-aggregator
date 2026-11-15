package com.flightaggregator.application.port.out;

import java.time.Instant;

/** Where the itineraries that search reads are kept. */
public interface ItineraryRepository {

  /**
   * Writes everything the writer pushes into a generation that search cannot see, and publishes it
   * only if {@code writeTo} returns normally. If {@code writeTo} throws, nothing changes and the
   * exception propagates.
   */
  Publication replaceInventory(Instant now, ItineraryWriter writer);
}
