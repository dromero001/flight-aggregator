package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.StoreTransactions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs each work as it is given, and reads the watched state just before the work begins and once
 * it has returned: what the work wrote through the stores shows in the second reading only.
 */
public final class WatchedTransactions implements StoreTransactions {

  private final Supplier<?> state;
  private final List<Object> seen = new ArrayList<>();

  public WatchedTransactions(Supplier<?> state) {
    this.state = state;
  }

  @Override
  public synchronized <T> T inOne(Supplier<T> work) {
    seen.add(state.get());
    T result = work.get();
    seen.add(state.get());
    return result;
  }

  /** Every reading, in order: two for each work that returned. */
  public synchronized List<Object> seen() {
    return List.copyOf(seen);
  }
}
