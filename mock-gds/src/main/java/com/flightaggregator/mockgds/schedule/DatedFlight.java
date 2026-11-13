package com.flightaggregator.mockgds.schedule;

import com.flightaggregator.mockgds.model.LegKey;
import java.time.OffsetDateTime;

/** One operation of a scheduled flight, with local departure and arrival times and their offsets. */
public record DatedFlight(ScheduledFlight scheduledFlight, OffsetDateTime departure, OffsetDateTime arrival) {

    /** The identity every provider selling this flight shares. */
    public LegKey key() {
        var route = scheduledFlight.route();
        return new LegKey(route.airline().code(), scheduledFlight.flightNumber(),
                route.origin().iata(), route.destination().iata(), departure.toInstant());
    }
}
