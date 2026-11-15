package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** An offer store that also remembers every {@code replace} call and counts the reads. */
public final class InMemoryOfferStore implements OfferStore {

  private final Map<Provider, List<Leg>> stored = new EnumMap<>(Provider.class);
  private final List<Provider> replaced = new ArrayList<>();
  private final List<Provider> read = new ArrayList<>();

  public InMemoryOfferStore with(Provider provider, List<Leg> offers) {
    stored.put(provider, List.copyOf(offers));
    return this;
  }

  @Override
  public synchronized void replace(Provider provider, List<Leg> offers) {
    replaced.add(provider);
    stored.put(provider, List.copyOf(offers));
  }

  @Override
  public synchronized List<Leg> offers(Provider provider) {
    read.add(provider);
    return stored.getOrDefault(provider, List.of());
  }

  @Override
  public synchronized List<Leg> offersOf(LegKey flight) {
    return stored.values().stream()
        .flatMap(List::stream)
        .filter(offer -> offer.key().equals(flight))
        .sorted(Comparator.comparing(Leg::price))
        .toList();
  }

  public synchronized List<Provider> replacedProviders() {
    return List.copyOf(replaced);
  }

  /** How many times the provider's offers were read. */
  public synchronized long reads(Provider provider) {
    return read.stream().filter(provider::equals).count();
  }
}
