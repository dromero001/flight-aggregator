package com.flightaggregator.mockgds.reference;

import static com.flightaggregator.mockgds.reference.Airport.hub;
import static com.flightaggregator.mockgds.reference.Airport.longHaul;
import static com.flightaggregator.mockgds.reference.Airport.spoke;

import java.util.List;

/** The fixed airports and airlines of the mock network: European hubs, their spokes and intercontinental routes. */
final class StandardCatalogue {

    static final List<Airport> AIRPORTS = List.of(
            hub("MAD", "Europe/Madrid", 40.472, -3.561),
            hub("BCN", "Europe/Madrid", 41.297, 2.078),
            hub("LHR", "Europe/London", 51.470, -0.454),
            hub("CDG", "Europe/Paris", 49.010, 2.548),
            hub("FRA", "Europe/Berlin", 50.033, 8.571),
            hub("AMS", "Europe/Amsterdam", 52.310, 4.768),
            hub("LIS", "Europe/Lisbon", 38.774, -9.134),
            hub("FCO", "Europe/Rome", 41.800, 12.239),
            hub("ZRH", "Europe/Zurich", 47.458, 8.548),

            spoke("SVQ", "Europe/Madrid", 37.418, -5.893, "MAD"),
            spoke("LPA", "Atlantic/Canary", 27.932, -15.387, "MAD"),
            spoke("PMI", "Europe/Madrid", 39.552, 2.739, "BCN"),
            spoke("IBZ", "Europe/Madrid", 38.873, 1.373, "BCN"),
            spoke("EDI", "Europe/London", 55.950, -3.373, "LHR"),
            spoke("MAN", "Europe/London", 53.354, -2.275, "LHR"),
            spoke("NCE", "Europe/Paris", 43.658, 7.216, "CDG"),
            spoke("BOD", "Europe/Paris", 44.828, -0.716, "CDG"),
            spoke("HAM", "Europe/Berlin", 53.630, 9.988, "FRA"),
            spoke("BER", "Europe/Berlin", 52.367, 13.503, "FRA"),
            spoke("EIN", "Europe/Amsterdam", 51.450, 5.375, "AMS"),
            spoke("OPO", "Europe/Lisbon", 41.248, -8.681, "LIS"),
            spoke("FAO", "Europe/Lisbon", 37.014, -7.966, "LIS"),
            spoke("NAP", "Europe/Rome", 40.886, 14.291, "FCO"),
            spoke("VCE", "Europe/Rome", 45.505, 12.352, "FCO"),
            spoke("GVA", "Europe/Zurich", 46.238, 6.109, "ZRH"),

            longHaul("JFK", "America/New_York", 40.640, -73.779, "MAD", "LHR", "CDG", "FRA", "AMS"),
            longHaul("MIA", "America/New_York", 25.793, -80.290, "MAD", "LHR"),
            longHaul("BOG", "America/Bogota", 4.702, -74.147, "MAD"),
            longHaul("MEX", "America/Mexico_City", 19.436, -99.072, "MAD"),
            longHaul("GRU", "America/Sao_Paulo", -23.432, -46.470, "LIS", "MAD"),
            longHaul("DXB", "Asia/Dubai", 25.253, 55.364, "LHR", "FRA"),
            longHaul("NRT", "Asia/Tokyo", 35.772, 140.393, "FRA", "CDG"));

    static final List<Airline> AIRLINES = List.of(
            new NetworkCarrier("IB", "Iberia", "MAD"),
            new NetworkCarrier("UX", "Air Europa", "MAD"),
            new NetworkCarrier("VY", "Vueling", "BCN"),
            new NetworkCarrier("BA", "British Airways", "LHR"),
            new NetworkCarrier("AF", "Air France", "CDG"),
            new NetworkCarrier("LH", "Lufthansa", "FRA"),
            new NetworkCarrier("KL", "KLM", "AMS"),
            new NetworkCarrier("TP", "TAP Air Portugal", "LIS"),
            new NetworkCarrier("AZ", "ITA Airways", "FCO"),
            new NetworkCarrier("LX", "Swiss", "ZRH"),
            new LowCostCarrier("FR", "Ryanair",
                    List.of("LPA", "MAN", "EDI", "EIN", "BER", "NAP", "FAO", "BOD", "OPO", "VCE", "HAM")),
            new LowCostCarrier("U2", "easyJet", List.of("CDG", "AMS", "LIS", "GVA")));

    private StandardCatalogue() {
    }
}
