package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** An offer store that also remembers every {@code replace} call. */
public final class InMemoryOfferStore implements OfferStore {

  private final Map<Provider, List<Leg>> stored = new EnumMap<>(Provider.class);
  private final List<Provider> replaced = new ArrayList<>();

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
    return stored.getOrDefault(provider, List.of());
  }

  public synchronized List<Provider> replacedProviders() {
    return List.copyOf(replaced);
  }
}
