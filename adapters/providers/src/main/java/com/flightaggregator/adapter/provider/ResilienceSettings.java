package com.flightaggregator.adapter.provider;

import java.time.Duration;

/**
 * How one provider is called and downloaded (§6.1). {@code requestTimeout} bounds one request,
 * {@code downloadTimeout} the whole download of all its pages; the production values are 2.5 s, 60
 * s, 3 attempts, 200 ms, 1000 records per page, 200 pages and 8 MB.
 */
public record ResilienceSettings(
    Duration requestTimeout,
    Duration downloadTimeout,
    int maxAttempts,
    Duration firstBackoff,
    int pageSize,
    int maxPages,
    int maxBodyBytes) {}
