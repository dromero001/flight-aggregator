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
   * the refresh's own failure is its own to report. One refresh runs at a time: one asked for while
   * another runs is dropped before any download, and the origin's claim given back ({@link
   * RefreshCoordination#release}), so a later search asks again.
   */
  void request(Airport origin);
}
