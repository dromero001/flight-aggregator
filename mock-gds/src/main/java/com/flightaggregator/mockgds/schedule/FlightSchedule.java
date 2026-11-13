package com.flightaggregator.mockgds.schedule;

import com.flightaggregator.mockgds.network.Route;
import com.flightaggregator.mockgds.random.SeededRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * The timetable of the mock network: which flights exist and on which dates they operate.
 *
 * <p>Whether a flight operates on a date depends only on the seed, the flight and the date, so the flights of a day
 * are the same whichever window they are requested in. This keeps the inventory stable while the window rolls.
 */
public final class FlightSchedule {

    private static final LocalTime FIRST_DEPARTURE = LocalTime.of(6, 0);
    private static final int DEPARTURE_SLOT_MINUTES = 5;
    private static final int DEPARTURE_SLOTS = 17 * 60 / DEPARTURE_SLOT_MINUTES;
    private static final int FIRST_FLIGHT_NUMBER = 1000;
    private static final int MAX_FLIGHT_NUMBER_GAP = 7;
    private static final int MAX_FLIGHT_NUMBER = 9999;

    private final List<ScheduledFlight> scheduledFlights;
    private final ScheduleParameters parameters;

    private FlightSchedule(List<ScheduledFlight> scheduledFlights, ScheduleParameters parameters) {
        this.scheduledFlights = List.copyOf(scheduledFlights);
        this.parameters = parameters;
    }

    public static FlightSchedule build(List<Route> routes, ScheduleParameters parameters) {
        var random = SeededRandom.derive(parameters.seed(), "schedule");
        var lastFlightNumberByAirline = new HashMap<String, Integer>();
        var scheduledFlights = new ArrayList<ScheduledFlight>();
        for (var route : routes) {
            int dailyFlights = random.nextInt(1, parameters.maxDailyFlightsPerRoute() + 1);
            for (int i = 0; i < dailyFlights; i++) {
                int flightNumber = nextFlightNumber(route, lastFlightNumberByAirline, random);
                scheduledFlights.add(new ScheduledFlight(route, flightNumber, randomDepartureTime(random)));
            }
        }
        return new FlightSchedule(scheduledFlights, parameters);
    }

    public List<ScheduledFlight> scheduledFlights() {
        return scheduledFlights;
    }

    /** Flights departing on the local dates from {@code firstDate} (inclusive) to {@code endDate} (exclusive). */
    public List<DatedFlight> flightsBetween(LocalDate firstDate, LocalDate endDate) {
        var flights = new ArrayList<DatedFlight>();
        for (var date = firstDate; date.isBefore(endDate); date = date.plusDays(1)) {
            for (var scheduledFlight : scheduledFlights) {
                if (operates(scheduledFlight, date)) {
                    flights.add(scheduledFlight.on(date));
                }
            }
        }
        return flights;
    }

    private boolean operates(ScheduledFlight flight, LocalDate date) {
        var random = SeededRandom.derive(parameters.seed(), "operates",
                flight.route().airline().code(), String.valueOf(flight.flightNumber()), date.toString());
        return random.nextDouble() < parameters.operatingProbability();
    }

    private static int nextFlightNumber(Route route, Map<String, Integer> lastByAirline, RandomGenerator random) {
        int next = lastByAirline.getOrDefault(route.airline().code(), FIRST_FLIGHT_NUMBER)
                + random.nextInt(1, MAX_FLIGHT_NUMBER_GAP + 1);
        if (next > MAX_FLIGHT_NUMBER) {
            throw new IllegalArgumentException("Too many flights for " + route.airline().code()
                    + ": flight numbers have at most four digits; lower maxDailyFlightsPerRoute");
        }
        lastByAirline.put(route.airline().code(), next);
        return next;
    }

    private static LocalTime randomDepartureTime(RandomGenerator random) {
        return FIRST_DEPARTURE.plusMinutes((long) random.nextInt(DEPARTURE_SLOTS) * DEPARTURE_SLOT_MINUTES);
    }
}
