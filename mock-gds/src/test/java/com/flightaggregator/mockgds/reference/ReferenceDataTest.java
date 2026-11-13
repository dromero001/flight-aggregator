package com.flightaggregator.mockgds.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReferenceDataTest {

    private final ReferenceData reference = ReferenceData.standard();

    @Test
    void airportCodesAreUniqueThreeLetterIataCodes() {
        assertThat(reference.airports())
                .extracting(Airport::iata)
                .allMatch(code -> code.matches("[A-Z]{3}"))
                .doesNotHaveDuplicates();
    }

    @Test
    void airlineCodesAreUniqueTwoCharacterIataCodes() {
        assertThat(reference.airlines())
                .extracting(Airline::code)
                .allMatch(code -> code.matches("[A-Z0-9]{2}"))
                .doesNotHaveDuplicates()
                .contains("IB", "VY", "UX", "FR", "U2", "LH", "AF", "BA", "KL", "TP", "AZ", "LX");
    }

    @Test
    void containsHubsSpokesAndLongHaulAirports() {
        assertThat(reference.airports())
                .extracting(Airport::role)
                .contains(AirportRole.HUB, AirportRole.SPOKE, AirportRole.LONG_HAUL);
    }

    @Test
    void spokesAndLongHaulAirportsConnectOnlyToExistingHubs() {
        reference.airports().stream()
                .filter(airport -> airport.role() != AirportRole.HUB)
                .forEach(airport -> assertThat(airport.gatewayHubs())
                        .as("gateways of %s", airport.iata())
                        .isNotEmpty()
                        .allMatch(hub -> reference.airport(hub).role() == AirportRole.HUB));
    }

    @Test
    void spokesHaveExactlyOneHomeHub() {
        reference.airports().stream()
                .filter(airport -> airport.role() == AirportRole.SPOKE)
                .forEach(airport -> assertThat(airport.gatewayHubs()).hasSize(1));
    }

    @Test
    void hubCarriersAreBasedAtExistingHubs() {
        reference.airlines().stream()
                .filter(airline -> airline.homeHub().isPresent())
                .forEach(airline -> assertThat(reference.airport(airline.homeHub().orElseThrow()).role())
                        .isEqualTo(AirportRole.HUB));
    }

    @Test
    void everyHubHasAtLeastOneHomeCarrier() {
        reference.airports().stream()
                .filter(airport -> airport.role() == AirportRole.HUB)
                .forEach(hub -> assertThat(reference.carriersBasedAt(hub)).as(hub.iata()).isNotEmpty());
    }

    @Test
    void offsetsFollowTheAirportTimeZoneIncludingDaylightSaving() {
        var winter = LocalDateTime.of(2026, 11, 20, 12, 0);
        var summer = LocalDateTime.of(2026, 7, 20, 12, 0);

        assertThat(offset("MAD", winter)).isEqualTo(ZoneOffset.ofHours(1));
        assertThat(offset("MAD", summer)).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(offset("JFK", winter)).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(offset("JFK", summer)).isEqualTo(ZoneOffset.ofHours(-4));
        assertThat(offset("BOG", winter)).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(offset("BOG", summer)).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(offset("LPA", winter)).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void distanceBetweenAirportsIsTheGreatCircleDistance() {
        assertThat(reference.airport("MAD").distanceKmTo(reference.airport("JFK"))).isBetween(5700.0, 5800.0);
        assertThat(reference.airport("MAD").distanceKmTo(reference.airport("BCN"))).isBetween(480.0, 500.0);
    }

    private ZoneOffset offset(String iata, LocalDateTime localTime) {
        return reference.airport(iata).zone().getRules().getOffset(localTime);
    }
}
