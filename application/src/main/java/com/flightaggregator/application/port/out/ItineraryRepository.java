package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Itinerary;
import java.time.Instant;

/** Where the itineraries that search reads are kept. */
public interface ItineraryRepository {

  /**
   * Writes everything the writer pushes into a generation that search cannot see, and publishes it
   * only if {@code writeTo} returns normally. If {@code writeTo} throws, nothing changes and the
   * exception propagates. {@code writeTo} runs inside the load, on the load's connection, and must
   * not use the storage ports.
   */
  Publication replaceInventory(Instant now, ItineraryWriter writer);

  /**
   * Replaces the active generation's itinerary of the same id by this re-quote of it (Q5): its legs
   * and every value derived from them change, its id, its departure and the generation do not.
   * Returns false, and changes nothing, when the active generation no longer holds that id.
   */
  boolean updateInPlace(Itinerary repriced);
}
