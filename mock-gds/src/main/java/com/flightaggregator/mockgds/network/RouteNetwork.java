package com.flightaggregator.mockgds.network;

import com.flightaggregator.mockgds.reference.Airline;
import com.flightaggregator.mockgds.reference.Airport;
import com.flightaggregator.mockgds.reference.AirportRole;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Derives the routes of the mock network from the reference data.
 *
 * <ul>
 *   <li>every pair of hubs is flown by the carriers based at either end;</li>
 *   <li>spokes and long-haul airports are flown only to their gateway hubs, by the carriers based there,
 *       so two spokes of different hubs are reachable only with two stops;</li>
 *   <li>low-cost carriers fly a fixed list of hub pairs.</li>
 * </ul>
 * Every connection is flown in both directions.
 */
public final class RouteNetwork {

    private record LowCostConnection(String airline, String airportA, String airportB) {
    }

    private record Connection(Airline airline, Airport airportA, Airport airportB) {

        Stream<Route> bothDirections() {
            return Stream.of(new Route(airline, airportA, airportB), new Route(airline, airportB, airportA));
        }
    }

    private static final List<LowCostConnection> LOW_COST_CONNECTIONS = List.of(
            new LowCostConnection("FR", "BCN", "FCO"),
            new LowCostConnection("FR", "MAD", "FCO"),
            new LowCostConnection("FR", "LIS", "BCN"),
            new LowCostConnection("FR", "AMS", "FCO"),
            new LowCostConnection("U2", "LHR", "ZRH"),
            new LowCostConnection("U2", "LHR", "AMS"),
            new LowCostConnection("U2", "CDG", "LIS"),
            new LowCostConnection("U2", "BCN", "AMS"));

    private RouteNetwork() {
    }

    public static List<Route> build(ReferenceData reference) {
        var connections = new LinkedHashSet<Connection>();
        connections.addAll(hubToHub(reference));
        connections.addAll(gatewayConnections(reference));
        connections.addAll(lowCost(reference));
        return connections.stream().flatMap(Connection::bothDirections).toList();
    }

    private static List<Connection> hubToHub(ReferenceData reference) {
        var hubs = reference.airports().stream().filter(airport -> airport.role() == AirportRole.HUB).toList();
        var connections = new ArrayList<Connection>();
        for (int i = 0; i < hubs.size(); i++) {
            for (int j = i + 1; j < hubs.size(); j++) {
                var hubA = hubs.get(i);
                var hubB = hubs.get(j);
                Stream.concat(reference.carriersBasedAt(hubA).stream(), reference.carriersBasedAt(hubB).stream())
                        .forEach(airline -> connections.add(new Connection(airline, hubA, hubB)));
            }
        }
        return connections;
    }

    private static List<Connection> gatewayConnections(ReferenceData reference) {
        return reference.airports().stream()
                .filter(airport -> airport.role() != AirportRole.HUB)
                .flatMap(airport -> airport.gatewayHubs().stream()
                        .map(reference::airport)
                        .flatMap(hub -> reference.carriersBasedAt(hub).stream()
                                .map(airline -> new Connection(airline, hub, airport))))
                .toList();
    }

    private static List<Connection> lowCost(ReferenceData reference) {
        return LOW_COST_CONNECTIONS.stream()
                .map(connection -> new Connection(
                        reference.airline(connection.airline()),
                        reference.airport(connection.airportA()),
                        reference.airport(connection.airportB())))
                .toList();
    }
}
