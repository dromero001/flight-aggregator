package com.flightaggregator.application.port.out;

import java.util.function.Supplier;

/**
 * One transaction across the stores: what the work writes through them commits together once it
 * returns, or not at all when it throws, and its exception then propagates as it was thrown.
 */
public interface StoreTransactions {

  <T> T inOne(Supplier<T> work);
}
