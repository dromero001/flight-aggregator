package com.flightaggregator.application.port.out;

import java.time.LocalDate;

/** What a published generation holds. */
public record Publication(
    long generation, long itineraries, long rowsInDefaultPartition, LocalDate lastPartitionDay) {}
