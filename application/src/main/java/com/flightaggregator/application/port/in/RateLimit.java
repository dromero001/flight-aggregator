package com.flightaggregator.application.port.in;

import java.time.Duration;

/** At most {@code requests} of one action from one client address in each fixed window (Q9). */
public record RateLimit(int requests, Duration window) {}
