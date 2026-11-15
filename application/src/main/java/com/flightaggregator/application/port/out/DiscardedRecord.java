package com.flightaggregator.application.port.out;

import static java.util.Objects.requireNonNull;

/**
 * A record left out of the inventory. The excerpt is the record as received (Alpha: the JSON
 * object; Beta: the segment's values as {@code name=value} pairs; Gamma: the line), cut to at most
 * 200 characters without splitting a surrogate pair.
 */
public record DiscardedRecord(DiscardReason reason, String excerpt) {

  private static final int EXCERPT_LIMIT = 200;

  public DiscardedRecord {
    requireNonNull(reason, "reason");
    excerpt = cut(requireNonNull(excerpt, "excerpt"));
  }

  private static String cut(String excerpt) {
    if (excerpt.length() <= EXCERPT_LIMIT) {
      return excerpt;
    }
    int end = EXCERPT_LIMIT;
    if (Character.isHighSurrogate(excerpt.charAt(end - 1))) {
      end--;
    }
    return excerpt.substring(0, end);
  }
}
