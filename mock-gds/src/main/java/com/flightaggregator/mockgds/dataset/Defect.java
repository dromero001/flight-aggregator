package com.flightaggregator.mockgds.dataset;

/**
 * How a malformed record is broken (§6.7 of the design). Each provider's serializer renders the
 * defect in its own format, so every adapter's parser meets every kind of bad input.
 */
public enum Defect {
  /** The flight number is omitted. */
  MISSING_FIELD,
  /**
   * The departure is not a valid ISO-8601 date-time; serializers write {@link #INVALID_DATE_TEXT}.
   */
  BAD_DATE,
  /** The price is the negated real price. */
  NEGATIVE_PRICE,
  /**
   * The destination is replaced by {@link #UNKNOWN_AIRPORT_CODE}, a code outside the reference
   * data.
   */
  UNKNOWN_AIRPORT;

  public static final String INVALID_DATE_TEXT = "2026-02-30T25:61:00+01:00";
  public static final String UNKNOWN_AIRPORT_CODE = "ZZZ";
}
