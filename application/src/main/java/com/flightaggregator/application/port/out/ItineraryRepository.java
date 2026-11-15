package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;
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
   * Replaces, in the published generation, every itinerary with a leg departing from the origin by
   * those the writer pushes, and publishes them as the next generation (a stale-route refresh,
   * Q2-B); the other itineraries, and when the inventory was last published, do not change. If
   * {@code writeTo} throws, nothing changes and the exception propagates. {@code writeTo} runs
   * inside the write, on its connection, and must not use the storage ports. The {@link
   * Publication} counts the itineraries the writer pushed. Before the first run has published,
   * there is nothing to rewrite: it throws {@link IllegalStateException} and changes nothing.
   */
  Publication replaceThrough(Airport origin, ItineraryWriter writer);

  /**
   * Replaces the active generation's itinerary of the same id by this re-quote of it (Q5): its legs
   * and every value derived from them change, its id, its departure and the generation do not.
   * Answers what became of it; anything but {@link StoreOutcome#STORED} changed nothing.
   */
  StoreOutcome updateInPlace(Itinerary repriced);
}
