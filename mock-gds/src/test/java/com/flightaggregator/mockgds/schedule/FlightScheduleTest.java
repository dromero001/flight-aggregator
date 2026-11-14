package com.flightaggregator.mockgds.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.flightaggregator.mockgds.network.Route;
import com.flightaggregator.mockgds.network.RouteNetwork;
import com.flightaggregator.mockgds.reference.LowCostCarrier;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FlightScheduleTest {

    private static final long SEED = 42;
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 13);

    private final List<Route> routes = RouteNetwork.build(ReferenceData.standard());

    @Test
    void departureAndArrivalCarryTheRealOffsetOfTheirAirports() {
        var flights = schedule(SEED, 1.0).flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(3));

        assertThat(flights).isNotEmpty().allSatisfy(flight -> {
            var route = flight.scheduledFlight().route();
            assertThat(flight.departure().getOffset())
                    .isEqualTo(route.origin().zone().getRules().getOffset(flight.departure().toInstant()));
            assertThat(flight.arrival().getOffset())
                    .isEqualTo(route.destination().zone().getRules().getOffset(flight.arrival().toInstant()));
            assertThat(Duration.between(flight.departure(), flight.arrival())).isEqualTo(route.blockTime());
        });
    }

    @Test
    void departuresFallOnTheRequestedLocalDatesBetweenSixAndElevenPm() {
        var flights = schedule(SEED, 1.0).flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(2));

        assertThat(flights).allSatisfy(flight -> {
            assertThat(flight.departure().toLocalDate()).isBetween(FIRST_DAY, FIRST_DAY.plusDays(1));
            assertThat(flight.departure().toLocalTime()).isBetween(LocalTime.of(6, 0), LocalTime.of(23, 0));
        });
    }

    @Test
    void offsetsFollowDaylightSavingChangesInsideTheWindow() {
        var europeanDstEnd = LocalDate.of(2026, 10, 25);
        var flights = schedule(SEED, 1.0).flightsBetween(europeanDstEnd.minusDays(1), europeanDstEnd.plusDays(2));

        var madridOffsets = flights.stream()
                .filter(flight -> flight.scheduledFlight().route().origin().iata().equals("MAD"))
                .collect(Collectors.groupingBy(flight -> flight.departure().toLocalDate(),
                        Collectors.mapping(flight -> flight.departure().getOffset(), Collectors.toSet())));

        assertThat(madridOffsets.get(europeanDstEnd.minusDays(1))).containsExactly(ZoneOffset.ofHours(2));
        assertThat(madridOffsets.get(europeanDstEnd.plusDays(1))).containsExactly(ZoneOffset.ofHours(1));
    }

    @Test
    void everyScheduledFlightHasAUniqueFlightNumberWithinItsAirline() {
        var scheduledFlights = schedule(SEED, 1.0).scheduledFlights();

        assertThat(scheduledFlights)
                .extracting(flight -> flight.route().airline().code() + flight.flightNumber())
                .doesNotHaveDuplicates();
        assertThat(scheduledFlights).extracting(ScheduledFlight::flightNumber).allMatch(number -> number >= 1 && number <= 9999);
    }

    @Test
    void everyRouteHasAtLeastOneScheduledFlight() {
        var scheduledRoutes = schedule(SEED, 1.0).scheduledFlights().stream().map(ScheduledFlight::route).distinct().toList();

        assertThat(scheduledRoutes).containsExactlyInAnyOrderElementsOf(routes);
    }

    @Test
    void lowCostRoutesGetUpToTheirOwnMaximumOfDailyFlights() {
        var flightsPerRoute = FlightSchedule.build(routes, new ScheduleParameters(SEED, 1, 4, 1.0)).scheduledFlights()
                .stream().collect(Collectors.groupingBy(ScheduledFlight::route, Collectors.counting()));

        assertThat(flightsPerRoute).allSatisfy((route, flights) -> assertThat(flights).as(route.toString())
                .isBetween(1L, route.airline() instanceof LowCostCarrier ? 4L : 1L));
        assertThat(flightsPerRoute).anySatisfy((route, flights) -> {
            assertThat(route.airline()).isInstanceOf(LowCostCarrier.class);
            assertThat(flights).isGreaterThan(1L);
        });
    }

    @Test
    void flightsOperateOnAboutTheConfiguredShareOfDays() {
        var schedule = schedule(SEED, 0.5);
        int days = 30;

        double operated = schedule.flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(days)).size();
        double possible = (double) schedule.scheduledFlights().size() * days;

        assertThat(operated / possible).isCloseTo(0.5, within(0.03));
    }

    @Test
    void theSameSeedProducesTheSameFlights() {
        assertThat(schedule(SEED, 0.7).flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(5)))
                .isEqualTo(schedule(SEED, 0.7).flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(5)));
    }

    @Test
    void aDifferentSeedProducesDifferentFlights() {
        assertThat(schedule(SEED, 0.7).flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(5)))
                .isNotEqualTo(schedule(SEED + 1, 0.7).flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(5)));
    }

    @Test
    void theFlightsOfADayDoNotDependOnTheWindowTheyAreRequestedIn() {
        var schedule = schedule(SEED, 0.7);
        var overlapDay = FIRST_DAY.plusDays(3);

        var fromEarlierWindow = schedule.flightsBetween(FIRST_DAY, FIRST_DAY.plusDays(10)).stream()
                .filter(flight -> flight.departure().toLocalDate().equals(overlapDay)).toList();
        var fromLaterWindow = schedule.flightsBetween(overlapDay, overlapDay.plusDays(10)).stream()
                .filter(flight -> flight.departure().toLocalDate().equals(overlapDay)).toList();

        assertThat(fromEarlierWindow).isNotEmpty().isEqualTo(fromLaterWindow);
    }

    @Test
    void refusesASizeThatWouldNeedFlightNumbersAboveFourDigits() {
        assertThatThrownBy(() -> FlightSchedule.build(routes, new ScheduleParameters(SEED, 2000, 2000, 1.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flight numbers");
    }

    private FlightSchedule schedule(long seed, double operatingProbability) {
        return FlightSchedule.build(routes, new ScheduleParameters(seed, 3, 3, operatingProbability));
    }
}
