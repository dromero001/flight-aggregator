package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;
import java.util.Set;

/** The airports the system knows. */
public interface AirportCatalogue {

  Set<Airport> airports();
}
