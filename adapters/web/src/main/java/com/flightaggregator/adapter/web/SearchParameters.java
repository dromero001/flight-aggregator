package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchQuery;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Money;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The search's query parameters as the client sent them, each absent or its text, and the query
 * they make (§4, §5.4, Q9). Each value must match its parameter's pattern in full, ASCII only, so a
 * code that upper-cases into another one (the dotless {@code ı}) is refused; a bad value is an
 * {@link InvalidParameterException} whose message starts with the parameter's name, and a bad
 * cursor an {@code InvalidCursorException}. A parameter the search does not take is refused too, so
 * a misspelt one never widens the search. The names, the patterns, the default and the largest page
 * size are the ones {@link SearchController} reads and documents in the contract.
 */
record SearchParameters(
    String origin,
    String destination,
    String carrier,
    String maxPrice,
    String sort,
    String limit,
    String cursor) {

  /**
   * Every parameter the search takes: this record's components, which {@link SearchController}
   * binds one to one, so the list cannot drift from them. They are in the order the docs and the
   * dashboard's filters list them.
   */
  static final List<String> NAMES =
      Arrays.stream(SearchParameters.class.getRecordComponents())
          .map(RecordComponent::getName)
          .toList();

  static final String AIRPORT = "^[A-Za-z]{3}$";
  static final String CARRIER = "^[A-Za-z0-9]{2}$";
  static final String AMOUNT = "^(?!0+(\\.0{1,2})?$)[0-9]{1,9}(\\.[0-9]{1,2})?$";
  static final String PRICE = "price";
  static final String DEPARTURE = "departure";
  static final String DURATION = "duration";
  static final String DEFAULT_SORT = PRICE;
  static final int DEFAULT_LIMIT = 20;
  static final int MAXIMUM_LIMIT = 100;

  private static final String LIMIT = "^[0-9]{1,3}$";
  private static final String MAX_PRICE_RULE =
      "maxPrice must be an amount in euros above 0, with at most two decimals, like 150 or 149.99";
  private static final String LIMIT_RULE =
      "limit must be a whole number from 1 to " + MAXIMUM_LIMIT;

  private static final Map<String, SortOrder> SORT_ORDERS =
      Map.of(PRICE, SortOrder.PRICE, DEPARTURE, SortOrder.DEPARTURE, DURATION, SortOrder.DURATION);

  /** Refuses the request when it names a parameter the search does not take. */
  static void refuseUnknown(Collection<String> sent) {
    List<String> unknown = sent.stream().filter(name -> !NAMES.contains(name)).toList();
    if (!unknown.isEmpty()) {
      throw new InvalidParameterException(
          (unknown.size() == 1 ? "Unknown parameter: " : "Unknown parameters: ")
              + String.join(", ", unknown)
              + ". The search takes "
              + String.join(", ", NAMES.subList(0, NAMES.size() - 1))
              + " and "
              + NAMES.getLast()
              + ".");
    }
  }

  SearchQuery toQuery() {
    return new SearchQuery(
        new SearchCriteria(
            airport("origin", origin),
            airport("destination", destination),
            maxPrice(maxPrice),
            carrier(carrier),
            sortOrder(sort)),
        limit(limit),
        Optional.ofNullable(cursor).map(CursorCodec::decode));
  }

  private static Optional<Airport> airport(String parameter, String code) {
    return matching(code, AIRPORT, parameter + " must be a three-letter airport code, like MAD")
        .map(value -> new Airport(value.toUpperCase(Locale.ROOT)));
  }

  private static Optional<Money> maxPrice(String amount) {
    return matching(amount, AMOUNT, MAX_PRICE_RULE).map(Money::euros);
  }

  private static Optional<Carrier> carrier(String code) {
    return matching(code, CARRIER, "carrier must be a two-character airline code, like IB")
        .map(value -> new Carrier(value.toUpperCase(Locale.ROOT)));
  }

  private static SortOrder sortOrder(String sort) {
    SortOrder order = SORT_ORDERS.get(sort == null ? DEFAULT_SORT : sort);
    if (order == null) {
      throw new InvalidParameterException("sort must be price, departure or duration");
    }
    return order;
  }

  private static int limit(String limit) {
    int value = matching(limit, LIMIT, LIMIT_RULE).map(Integer::parseInt).orElse(DEFAULT_LIMIT);
    if (value < 1 || value > MAXIMUM_LIMIT) {
      throw new InvalidParameterException(LIMIT_RULE);
    }
    return value;
  }

  /** The value, when the client sent one, refused with the rule unless it matches in full. */
  private static Optional<String> matching(String value, String pattern, String rule) {
    if (value != null && !value.matches(pattern)) {
      throw new InvalidParameterException(rule);
    }
    return Optional.ofNullable(value);
  }
}
