package com.flightaggregator.mockgds.schedule;

import com.flightaggregator.mockgds.network.Route;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/** A flight number on a route, departing every operating day at the same local time of its origin. */
public record ScheduledFlight(Route route, int flightNumber, LocalTime departureTime) {

    public ScheduledFlight {
        Objects.requireNonNull(route);
        Objects.requireNonNull(departureTime);
    }

    /** The flight on a local date of its origin; both times carry their airport's offset on that date. */
    public DatedFlight on(LocalDate date) {
        var departure = ZonedDateTime.of(date, departureTime, route.origin().zone());
        var arrival = departure.plus(route.blockTime()).withZoneSameInstant(route.destination().zone());
        return new DatedFlight(this, departure.toOffsetDateTime(), arrival.toOffsetDateTime());
    }
}
