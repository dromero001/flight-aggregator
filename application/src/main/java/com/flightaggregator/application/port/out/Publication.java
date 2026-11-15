package com.flightaggregator.application.port.out;

import java.time.Instant;

/**
 * What a write published: its generation, the itineraries its writer pushed (a run's whole
 * inventory, or a refresh's itineraries through its origin), and the rows of the generation's
 * {@code DEFAULT} partition. {@code partitionedUntil} is where its day partitions end: a departure
 * before it is in the partition of its day, a later one in the {@code DEFAULT} partition.
 */
public record Publication(
    long generation, long itineraries, long rowsInDefaultPartition, Instant partitionedUntil) {}
