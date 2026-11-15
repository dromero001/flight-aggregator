package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.ItineraryId;

/** Confirms the price of a chosen itinerary live, before it is sold (§6.3, §6.5). */
public interface RepriceItinerary {

  RepriceResult reprice(ItineraryId id);
}
