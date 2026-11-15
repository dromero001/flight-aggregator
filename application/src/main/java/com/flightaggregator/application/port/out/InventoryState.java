package com.flightaggregator.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * The generation search reads, and when the last full run was published; generation 0 and no time
 * before the first one.
 */
public record InventoryState(long generation, Optional<Instant> publishedAt) {}
