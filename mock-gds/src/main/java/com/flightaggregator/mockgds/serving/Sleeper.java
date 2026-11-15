package com.flightaggregator.mockgds.serving;

import java.time.Duration;

/**
 * Waits for a duration. Injectable, so tests can record the waits instead of sitting through them.
 */
@FunctionalInterface
interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;
}
