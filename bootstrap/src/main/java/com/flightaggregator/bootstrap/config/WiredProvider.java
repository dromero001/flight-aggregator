package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FlightProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;

/**
 * One provider's adapter and the parts it was built from, all derived from the provider by {@code
 * ProvidersConfig.wire}. The parts are kept so that the wiring can be checked: the adapter, its
 * endpoint and its download must name the same provider, or its failures would be reported under
 * another one.
 */
record WiredProvider(
    FlightProvider adapter,
    ProviderEndpoint endpoint,
    ResilienceSettings settings,
    CircuitBreaker circuitBreaker,
    TimeLimiter timeLimiter,
    PagedDownload download) {}
