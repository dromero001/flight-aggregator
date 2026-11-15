package com.flightaggregator.adapter.web;

/**
 * The itinerary a path names is not in the inventory being served; {@link ProblemResponses} answers
 * it as a 404.
 */
final class ItineraryNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ItineraryNotFoundException() {
    super("id names no itinerary in the current inventory: search again for the flights on sale.");
  }
}
