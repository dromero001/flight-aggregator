package com.flightaggregator.mockgds.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.reference.Airport;
import com.flightaggregator.mockgds.reference.AirportRole;
import com.flightaggregator.mockgds.reference.LowCostCarrier;
import com.flightaggregator.mockgds.reference.NetworkCarrier;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RouteNetworkTest {

  private final ReferenceData reference = ReferenceData.standard();
  private final List<Route> routes = RouteNetwork.build(reference);

  @Test
  void everyRouteIsFlownInBothDirectionsByTheSameAirline() {
    assertThat(routes)
        .allSatisfy(
            route ->
                assertThat(routes)
                    .anyMatch(
                        other ->
                            other.airline().equals(route.airline())
                                && other.origin().equals(route.destination())
                                && other.destination().equals(route.origin())));
  }

  @Test
  void everyPairOfHubsIsConnected() {
    var hubs =
        reference.airports().stream().filter(airport -> airport.role() == AirportRole.HUB).toList();
    for (var from : hubs) {
      for (var to : hubs) {
        if (!from.equals(to)) {
          assertThat(routes)
              .as("%s-%s", from.iata(), to.iata())
              .anyMatch(route -> route.origin().equals(from) && route.destination().equals(to));
        }
      }
    }
  }

  @Test
  void networkCarriersServeSpokesAndLongHaulAirportsOnlyFromTheirGatewayHubs() {
    assertThat(routes)
        .filteredOn(route -> route.airline() instanceof NetworkCarrier)
        .allSatisfy(
            route -> {
              assertServedFromGateway(route.origin(), route.destination());
              assertServedFromGateway(route.destination(), route.origin());
            });
  }

  @Test
  void everyRouteStartsOrEndsAtABaseOfItsAirline() {
    assertThat(routes)
        .allSatisfy(
            route ->
                assertThat(route.airline().bases())
                    .as(route.toString())
                    .containsAnyOf(route.origin().iata(), route.destination().iata()));
  }

  @Test
  void someRoutesAreFlownByMoreThanOneAirline() {
    var airlinesByAirportPair =
        routes.stream()
            .collect(
                Collectors.groupingBy(
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
  void lowCostCarriersFlyPointToPointBetweenEveryPairOfTheirBases() {
    var fixture =
        new ReferenceData(
            List.of(
                airport("BCN", AirportRole.HUB, 41.297, 2.078),
                    airport("PMI", AirportRole.SPOKE, 39.552, 2.739),
                airport("EIN", AirportRole.SPOKE, 51.450, 5.375),
                    airport("NAP", AirportRole.SPOKE, 40.886, 14.291)),
            List.of(new LowCostCarrier("FR", "Ryanair", List.of("PMI", "EIN", "NAP"))));

    assertThat(RouteNetwork.build(fixture))
        .extracting(Route::toString)
        .containsExactlyInAnyOrder(
            "FR PMI-EIN", "FR EIN-PMI", "FR PMI-NAP", "FR NAP-PMI", "FR EIN-NAP", "FR NAP-EIN");
  }

  @Test
  void vuelingFliesPointToPointBetweenBarcelonaAndTheBalearics() {
    assertThat(routes)
        .filteredOn(route -> route.airline().code().equals("VY"))
        .extracting(Route::toString)
        .containsExactlyInAnyOrder(
            "VY BCN-PMI", "VY PMI-BCN", "VY BCN-IBZ", "VY IBZ-BCN", "VY PMI-IBZ", "VY IBZ-PMI");
  }

  @Test
  void blockTimeGrowsWithDistanceAndIsRoundedToFiveMinutes() {
    var shortHaul = route("MAD", "BCN");
    var longHaul = route("MAD", "JFK");

    assertThat(shortHaul.blockTime()).isBetween(Duration.ofMinutes(60), Duration.ofMinutes(90));
    assertThat(longHaul.blockTime()).isBetween(Duration.ofHours(7), Duration.ofHours(8));
    assertThat(routes).allSatisfy(route -> assertThat(route.blockTime().toMinutes() % 5).isZero());
  }

  private static Airport airport(String iata, AirportRole role, double latitude, double longitude) {
    var gatewayHubs = role == AirportRole.HUB ? List.<String>of() : List.of("BCN");
    return new Airport(iata, ZoneId.of("Europe/Madrid"), latitude, longitude, role, gatewayHubs);
  }

  private void assertServedFromGateway(Airport airport, Airport otherEnd) {
    if (airport.role() != AirportRole.HUB) {
      assertThat(airport.gatewayHubs())
          .as("%s served from %s", airport.iata(), otherEnd.iata())
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
        .filter(
            route ->
                route.origin().iata().equals(origin)
                    && route.destination().iata().equals(destination))
        .findFirst()
        .orElseThrow();
  }
}
