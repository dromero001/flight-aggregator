package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.model.LegKey;
import java.util.Optional;

/** Which of a provider's records a request asks for. */
public record RecordFilter(Optional<String> origin, Optional<LegKey> flight) {

  public static RecordFilter all() {
    return new RecordFilter(Optional.empty(), Optional.empty());
  }

  public static RecordFilter fromOrigin(String origin) {
    return new RecordFilter(Optional.of(origin), Optional.empty());
  }

  public static RecordFilter flight(LegKey flight) {
    return new RecordFilter(Optional.empty(), Optional.of(flight));
  }

  boolean matches(ProviderRecord record) {
    return origin.map(o -> o.equals(record.leg().origin())).orElse(true)
        && flight.map(f -> f.equals(record.leg().key())).orElse(true);
  }
}
