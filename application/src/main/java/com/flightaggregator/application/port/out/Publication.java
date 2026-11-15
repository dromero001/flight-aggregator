package com.flightaggregator.application.port.out;

import java.time.Instant;

/**
 * What a published generation holds. {@code partitionedUntil} is where its day partitions end: a
 * departure before it is in the partition of its day, a later one in the {@code DEFAULT} partition.
 */
public record Publication(
    long generation, long itineraries, long rowsInDefaultPartition, Instant partitionedUntil) {}
