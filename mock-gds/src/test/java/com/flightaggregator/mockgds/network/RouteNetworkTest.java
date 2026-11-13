package com.flightaggregator.mockgds.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.reference.Airport;
import com.flightaggregator.mockgds.reference.AirportRole;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RouteNetworkTest {

    private final ReferenceData reference = ReferenceData.standard();
    private final List<Route> routes = RouteNetwork.build(reference);

    @Test
    void everyRouteIsFlownInBothDirectionsByTheSameAirline() {
        assertThat(routes).allSatisfy(route -> assertThat(routes)
                .anyMatch(other -> other.airline().equals(route.airline())
                        && other.origin().equals(route.destination())
                        && other.destination().equals(route.origin())));
    }

    @Test
    void everyPairOfHubsIsConnected() {
        var hubs = reference.airports().stream().filter(airport -> airport.role() == AirportRole.HUB).toList();
        for (var from : hubs) {
            for (var to : hubs) {
                if (!from.equals(to)) {
                    assertThat(routes).as("%s-%s", from.iata(), to.iata())
                            .anyMatch(route -> route.origin().equals(from) && route.destination().equals(to));
                }
            }
        }
    }

    @Test
    void spokesAndLongHaulAirportsAreServedOnlyFromTheirGatewayHubs() {
        assertThat(routes).allSatisfy(route -> {
            assertServedFromGateway(route.origin(), route.destination());
            assertServedFromGateway(route.destination(), route.origin());
        });
    }

    @Test
    void hubRoutesAreFlownByCarriersBasedAtOneOfTheirEndsOrByLowCostCarriers() {
        assertThat(routes).allSatisfy(route -> assertThat(route.airline().homeHub()
                .map(hub -> hub.equals(route.origin().iata()) || hub.equals(route.destination().iata()))
                .orElse(true)).as(route.toString()).isTrue());
    }

    @Test
    void someRoutesAreFlownByMoreThanOneAirline() {
        var airlinesByAirportPair = routes.stream().collect(Collectors.groupingBy(
                route -> route.origin().iata() + route.destination().iata(),
                Collectors.mapping(route -> route.airline().code(), Collectors.toSet())));

        assertThat(airlinesByAirportPair.values()).anyMatch(airlines -> airlines.size() > 1);
    }

    @Test
    void spokesOfDifferentHubsAreOnlyReachableWithTwoStops() {
        var sevilla = reference.airport("SVQ");
        var nice = reference.airport("NCE");

        assertThat(neighbours(sevilla)).containsExactly("MAD");
        assertThat(neighbours(nice)).containsExactly("CDG");
        assertThat(neighbours(reference.airport("MAD"))).contains("CDG");
    }

    @Test
    void blockTimeGrowsWithDistanceAndIsRoundedToFiveMinutes() {
        var shortHaul = route("MAD", "BCN");
        var longHaul = route("MAD", "JFK");

        assertThat(shortHaul.blockTime()).isBetween(Duration.ofMinutes(60), Duration.ofMinutes(90));
        assertThat(longHaul.blockTime()).isBetween(Duration.ofHours(7), Duration.ofHours(8));
        assertThat(routes).allSatisfy(route -> assertThat(route.blockTime().toMinutes() % 5).isZero());
    }

    private void assertServedFromGateway(Airport airport, Airport otherEnd) {
        if (airport.role() != AirportRole.HUB) {
            assertThat(airport.gatewayHubs()).as("%s served from %s", airport.iata(), otherEnd.iata())
                    .contains(otherEnd.iata());
        }
    }

    private Set<String> neighbours(Airport airport) {
        return routes.stream()
                .filter(route -> route.origin().equals(airport))
                .map(route -> route.destination().iata())
                .collect(Collectors.toSet());
    }

    private Route route(String origin, String destination) {
        return routes.stream()
                .filter(route -> route.origin().iata().equals(origin) && route.destination().iata().equals(destination))
                .findFirst()
                .orElseThrow();
    }
}
