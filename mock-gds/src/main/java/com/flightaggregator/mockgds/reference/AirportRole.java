package com.flightaggregator.mockgds.reference;

/** Position of an airport in the mock route network. */
public enum AirportRole {
    /** Connected to every other hub and to its spokes and long-haul destinations. */
    HUB,
    /** Regional airport served only from its single home hub. */
    SPOKE,
    /** Intercontinental airport served only from a few gateway hubs. */
    LONG_HAUL
}
