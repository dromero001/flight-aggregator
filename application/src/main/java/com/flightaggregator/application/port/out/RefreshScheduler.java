package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;

/**
 * Starts the background refresh of a stale origin (§4.1, §6.4). Search asks for it after winning
 * the origin's claim, on its own request thread, so a search must neither wait for the refresh nor
 * fail with it.
 */
public interface RefreshScheduler {

  /**
   * Starts the origin's refresh and returns at once, without waiting for it; it never throws, so
   * the refresh's own failure is its own to report.
   */
  void request(Airport origin);
}
