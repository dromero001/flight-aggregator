package com.flightaggregator.application.service;

/** The run would write more itineraries than the configured ceiling allows. */
final class IngestionCeiling extends RuntimeException {

  private static final long serialVersionUID = 1L;

  IngestionCeiling(long maxItineraries) {
    super("More than " + maxItineraries + " itineraries");
  }
}
