package com.flightaggregator.mockgds.network;

import com.flightaggregator.mockgds.reference.Airline;
import com.flightaggregator.mockgds.reference.Airport;
import com.flightaggregator.mockgds.reference.AirportRole;
import com.flightaggregator.mockgds.reference.LowCostCarrier;
import com.flightaggregator.mockgds.reference.NetworkCarrier;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Derives the routes of the mock network from the reference data: each airline flies the routes of its business
 * model, in both directions.
 *
 * <ul>
 *   <li>a network carrier flies from its hub to every other hub and to the spokes and long-haul airports the hub
 *       gateways, so a spoke that no low-cost carrier serves reaches the spokes of other hubs only with two stops;</li>
 *   <li>a low-cost carrier flies point-to-point between every pair of its bases.</li>
 * </ul>
 */
public final class RouteNetwork {

    private RouteNetwork() {
    }

    public static List<Route> build(ReferenceData reference) {
        return reference.airlines().stream()
                .flatMap(airline -> switch (airline) {
                    case NetworkCarrier carrier -> fromHub(carrier, reference);
                    case LowCostCarrier carrier -> betweenBases(carrier, reference);
                })
                .toList();
    }

    private static Stream<Route> fromHub(NetworkCarrier carrier, ReferenceData reference) {
        var hub = reference.airport(carrier.hub());
        return reference.airports().stream()
                .filter(airport -> !airport.equals(hub))
                .filter(airport -> airport.role() == AirportRole.HUB || airport.gatewayHubs().contains(hub.iata()))
                .flatMap(airport -> bothDirections(carrier, hub, airport));
    }

    private static Stream<Route> betweenBases(LowCostCarrier carrier, ReferenceData reference) {
        var bases = carrier.bases().stream().map(reference::airport).toList();
        return IntStream.range(0, bases.size()).boxed()
                .flatMap(i -> bases.subList(i + 1, bases.size()).stream()
                        .flatMap(other -> bothDirections(carrier, bases.get(i), other)));
    }

    private static Stream<Route> bothDirections(Airline airline, Airport airportA, Airport airportB) {
        return Stream.of(new Route(airline, airportA, airportB), new Route(airline, airportB, airportA));
    }
}
