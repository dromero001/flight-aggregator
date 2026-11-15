package com.flightaggregator.adapter.provider;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.LegKey;
import java.util.List;
import java.util.Optional;

/**
 * The query parameters every provider's listing shares besides its paging (mock-gds, "Origin filter
 * and quote"): none for every leg, an {@code origin} for that airport's legs, or a flight's five
 * key parts for its quote, the departure a UTC instant ending in {@code Z}. The template follows
 * the paging parameters, and each variable is encoded into it.
 */
record ListingQuery(String template, List<Object> variables) {

  private static final ListingQuery EVERY_LEG = new ListingQuery("", List.of());

  ListingQuery {
    variables = List.copyOf(variables);
  }

  /** One origin's legs, or every leg when the origin is empty. */
  static ListingQuery from(Optional<Airport> origin) {
    return origin
        .map(airport -> new ListingQuery("&origin={origin}", List.of(airport.code())))
        .orElse(EVERY_LEG);
  }

  /** The one flight with this key. */
  static ListingQuery of(LegKey flight) {
    return new ListingQuery(
        "&carrier={carrier}&flightNumber={flightNumber}&origin={origin}"
            + "&destination={destination}&departure={departure}",
        List.of(
            flight.carrier().code(),
            flight.flightNumber(),
            flight.origin().code(),
            flight.destination().code(),
            flight.departure().toString()));
  }
}
