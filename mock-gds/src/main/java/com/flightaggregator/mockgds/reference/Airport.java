package com.flightaggregator.mockgds.reference;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * An airport of the mock network.
 *
 * @param iata        three-letter IATA code
 * @param zone        IANA time zone, used to produce the real UTC offset of every departure and arrival
 * @param latitude    degrees, used to derive flight durations and fares
 * @param longitude   degrees
 * @param role        position in the route network
 * @param gatewayHubs hubs this airport is connected to; empty for hubs, which connect to every other hub
 */
public record Airport(String iata, ZoneId zone, double latitude, double longitude, AirportRole role,
                      List<String> gatewayHubs) {

    private static final double EARTH_RADIUS_KM = 6371.0;

    public Airport {
        Objects.requireNonNull(iata);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(role);
        gatewayHubs = List.copyOf(gatewayHubs);
    }

    static Airport hub(String iata, String zone, double latitude, double longitude) {
        return new Airport(iata, ZoneId.of(zone), latitude, longitude, AirportRole.HUB, List.of());
    }

    static Airport spoke(String iata, String zone, double latitude, double longitude, String homeHub) {
        return new Airport(iata, ZoneId.of(zone), latitude, longitude, AirportRole.SPOKE, List.of(homeHub));
    }

    static Airport longHaul(String iata, String zone, double latitude, double longitude, String... gatewayHubs) {
        return new Airport(iata, ZoneId.of(zone), latitude, longitude, AirportRole.LONG_HAUL, List.of(gatewayHubs));
    }

    /** Great-circle (haversine) distance in kilometres. */
    public double distanceKmTo(Airport other) {
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(other.longitude - longitude);
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
