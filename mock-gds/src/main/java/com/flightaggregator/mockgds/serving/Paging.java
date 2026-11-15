package com.flightaggregator.mockgds.serving;

/** The paging rules every provider shares: strict integers, and a size between 1 and 1000. */
public final class Paging {

  public static final int DEFAULT_LIMIT = 500;
  public static final int MAX_LIMIT = 1000;

  private Paging() {}

  /** The limit asked for, or the default when the parameter is absent. */
  public static int limit(String raw) {
    return size("Limit", raw);
  }

  /** The page size asked for, or the default when the parameter is absent. */
  public static int pageSize(String raw) {
    return size("Page size", raw);
  }

  /**
   * A strict integer parameter of at least {@code min}: plain digits with an optional minus, so
   * {@code +5} and {@code 05} are refused.
   */
  public static int integer(String name, String raw, int min) {
    int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new InvalidRequestException(name + " must be a number: " + raw, e);
    }
    if (!Integer.toString(value).equals(raw)) {
      throw new InvalidRequestException(name + " must be a plain number: " + raw);
    }
    if (value < min) {
      throw new InvalidRequestException(name + " must be at least " + min);
    }
    return value;
  }

  private static int size(String name, String raw) {
    if (raw == null) {
      return DEFAULT_LIMIT;
    }
    var size = integer(name, raw, 1);
    if (size > MAX_LIMIT) {
      throw new InvalidRequestException(name + " must be between 1 and " + MAX_LIMIT);
    }
    return size;
  }
}
