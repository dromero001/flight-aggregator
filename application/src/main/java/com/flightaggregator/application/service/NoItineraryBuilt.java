package com.flightaggregator.application.service;

/** The stored offers build no itinerary: an empty generation must not replace the good one. */
final class NoItineraryBuilt extends RuntimeException {

  private static final long serialVersionUID = 1L;

  NoItineraryBuilt() {
    super("The stored offers build no itinerary");
  }
}
