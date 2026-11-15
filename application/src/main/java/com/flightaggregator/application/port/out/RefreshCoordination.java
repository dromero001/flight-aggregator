package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Airport;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared by every instance, so that many searches finding the same stale origin start one refresh,
 * not one each (§6.2). Unavailable, it knows of no refresh and grants no claim, so no refresh
 * starts until it is back.
 */
public interface RefreshCoordination {

  /** When the origin was last refreshed, while that is within the freshness threshold. */
  Optional<Instant> refreshedAt(Airport origin);

  /** Whether this caller may refresh the origin: true for one caller, until the claim expires. */
  boolean claim(Airport origin);

  /** Records that the origin was refreshed at this time, remembered for the freshness threshold. */
  void refreshed(Airport origin, Instant at);
}
