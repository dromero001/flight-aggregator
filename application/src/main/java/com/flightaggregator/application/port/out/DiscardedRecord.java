package com.flightaggregator.application.port.out;

import static java.util.Objects.requireNonNull;

/**
 * A record left out of the inventory. The excerpt is the record as received (Alpha: the JSON
 * object; Beta: the segment's values as {@code name=value} pairs; Gamma: the line), with control
 * characters escaped, cut to at most 200 characters without splitting a surrogate pair.
 */
public record DiscardedRecord(DiscardReason reason, String excerpt) {

  private static final int EXCERPT_LIMIT = 200;

  public DiscardedRecord {
    requireNonNull(reason, "reason");
    excerpt = cut(escaped(requireNonNull(excerpt, "excerpt")));
  }

  /** Line breaks and other control characters of provider text never reach a log line raw. */
  private static String escaped(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (Character.isISOControl(c)) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
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
