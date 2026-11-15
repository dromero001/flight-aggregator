package com.flightaggregator.adapter.provider;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.LegKey;
import java.time.Instant;
import org.springframework.web.util.DefaultUriBuilderFactory;

/** What the provider tests share. */
final class Fixtures {

  /** IB3170 MAD-LHR, leaving at 08:15 in Madrid: the flight of the tests' records. */
  static final LegKey FLIGHT =
      new LegKey(
          new Carrier("IB"),
          3170,
          new Airport("MAD"),
          new Airport("LHR"),
          Instant.parse("2026-11-20T07:15:00Z"));

  private Fixtures() {}

  /** The path as the provider's HTTP client expands it: each variable encoded into the template. */
  static String path(PageRequest request) {
    return new DefaultUriBuilderFactory()
        .expand(request.pathTemplate(), request.variables())
        .toString();
  }
}
