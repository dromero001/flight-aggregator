package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The inventory of the three mock providers at one instant. */
public final class Dataset {

  private final Map<Provider, List<ProviderRecord>> recordsByProvider;

  Dataset(List<ProviderRecord> records) {
    var grouped = records.stream().collect(Collectors.groupingBy(ProviderRecord::provider));
    this.recordsByProvider =
        Arrays.stream(Provider.values())
            .collect(
                Collectors.toUnmodifiableMap(
                    Function.identity(),
                    provider -> List.copyOf(grouped.getOrDefault(provider, List.of()))));
  }

  /** Everything the provider serves, valid and malformed records, in departure order. */
  public List<ProviderRecord> recordsFor(Provider provider) {
    return recordsByProvider.get(provider);
  }

  /** The legs of every provider that were served correctly. */
  public List<Leg> validLegs() {
    return Arrays.stream(Provider.values())
        .flatMap(provider -> recordsFor(provider).stream())
        .filter(ValidLeg.class::isInstance)
        .map(ProviderRecord::leg)
        .toList();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Dataset dataset && recordsByProvider.equals(dataset.recordsByProvider);
  }

  @Override
  public int hashCode() {
    return recordsByProvider.hashCode();
  }
}
