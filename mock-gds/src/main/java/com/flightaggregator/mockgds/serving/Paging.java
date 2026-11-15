package com.flightaggregator.mockgds.serving;

/** The page-size rules every provider shares: default 500, between 1 and 1000. */
public final class Paging {

  public static final int DEFAULT_LIMIT = 500;
  public static final int MAX_LIMIT = 1000;

  private Paging() {}

  /** The limit asked for, or the default when the parameter is absent. */
  public static int limit(String raw) {
    var limit = raw == null ? DEFAULT_LIMIT : parse(raw);
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidRequestException("Limit must be between 1 and " + MAX_LIMIT);
    }
    return limit;
  }

  private static int parse(String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new InvalidRequestException("Limit must be a number: " + raw, e);
    }
  }
}
