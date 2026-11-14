package com.flightaggregator.domain;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The identity of an itinerary: a name-based UUID of its legs' canonical keys. It does not depend
 * on prices or providers, so a requoted itinerary keeps its id.
 */
public record ItineraryId(UUID value) {

  public static ItineraryId of(List<LegKey> keys) {
    String canonical = keys.stream().map(LegKey::toString).collect(Collectors.joining("|"));
    return new ItineraryId(UUID.nameUUIDFromBytes(canonical.getBytes(UTF_8)));
  }
}
