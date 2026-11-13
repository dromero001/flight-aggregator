package com.flightaggregator.mockgds.schedule;

import java.time.OffsetDateTime;

/** One operation of a scheduled flight, with local departure and arrival times and their offsets. */
public record DatedFlight(ScheduledFlight scheduledFlight, OffsetDateTime departure, OffsetDateTime arrival) {
}
