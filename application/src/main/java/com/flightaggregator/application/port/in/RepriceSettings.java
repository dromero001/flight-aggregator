package com.flightaggregator.application.port.in;

import java.time.Duration;

/**
 * The bounds of one re-price (Q9): how many alternatives it tries after the choice, and how long
 * after it starts it may still start a quote.
 */
public record RepriceSettings(int maxAlternatives, Duration budget) {}
