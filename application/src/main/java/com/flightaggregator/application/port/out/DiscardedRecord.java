package com.flightaggregator.application.port.out;

import static java.util.Objects.requireNonNull;

/**
 * A record left out of the inventory. The excerpt is the record as received (Alpha: the JSON
 * object; Beta: the segment's values as {@code name=value} pairs; Gamma: the line), escaped and cut
 * by {@link ProviderText#escaped}.
 */
public record DiscardedRecord(DiscardReason reason, String excerpt) {

  public DiscardedRecord {
    requireNonNull(reason, "reason");
    excerpt = ProviderText.escaped(requireNonNull(excerpt, "excerpt"));
  }
}
