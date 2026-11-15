package com.flightaggregator.application.port.in;

/** The public actions limited per client address, each with its own counter (§6.6, Q9). */
public enum RateLimitedAction {
  SEARCH,
  REPRICE
}
