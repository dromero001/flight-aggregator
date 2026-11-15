package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Provider;

/** Where the records left out of the inventory are reported. */
public interface DiscardedRecords {

  void discarded(Provider provider, DiscardedRecord record);
}
