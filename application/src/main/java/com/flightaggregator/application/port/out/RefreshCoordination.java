package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared by every instance, so that many searches finding the same stale origin start one refresh,
 * not one each (§6.2). Unavailable, it knows of no refresh and grants no claim, so no refresh
 * starts until it is back. A claim, or a mark, whose command timed out may still land when a hung
 * Valkey resumes, and a claim may succeed although its reply timed out: that origin is then claimed
 * by no one until the claim expires, as after a refresh that crashed.
 */
public interface RefreshCoordination {

  /** When the origin was last refreshed, while that is within the freshness threshold. */
  Optional<Instant> refreshedAt(Airport origin);

  /** Whether this caller may refresh the origin: true for one caller, until the claim expires. */
  boolean claim(Airport origin);

  /**
   * Gives back the claim of a refresh that did not start, so the next search that finds the origin
   * stale claims it at once, instead of after the claim expires.
   */
  void release(Airport origin);

  /** Records that the origin was refreshed at this time, remembered for the freshness threshold. */
  void refreshed(Airport origin, Instant at);
}
