package com.flightaggregator.application.port.out;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import java.util.Optional;

/**
 * Where a page of a search is cached: under the generation it was read from, so a new generation
 * leaves every cached page behind at once, with no key scanned or deleted (§6.2), and under its
 * criteria and page size.
 */
public record SearchKey(long generation, SearchCriteria criteria, int limit) {

  /**
   * {@code search:<generation>:<sort>:<origin|->:<destination|->:<carrier|->:<maxPrice|->:<limit>},
   * an absent filter written {@code -}. Codes and amounts are normalised by their types, so equal
   * criteria always give the same key.
   */
  public String value() {
    return String.join(
        ":",
        "search",
        Long.toString(generation),
        criteria.sort().name(),
        orDash(criteria.origin().map(Airport::code)),
        orDash(criteria.destination().map(Airport::code)),
        orDash(criteria.carrier().map(Carrier::code)),
        orDash(criteria.maxPrice().map(price -> price.amount().toPlainString())),
        Integer.toString(limit));
  }

  private static String orDash(Optional<String> filter) {
    return filter.orElse("-");
  }
}
