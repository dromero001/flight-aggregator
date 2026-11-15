package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.domain.Provider;
import java.util.ArrayList;
import java.util.List;

/** Keeps what it receives, as {@code PROVIDER:record}. */
public final class RecordingDiscardedRecords implements DiscardedRecords {

  public record Entry(Provider provider, DiscardedRecord record) {}

  private final List<Entry> entries = new ArrayList<>();

  @Override
  public synchronized void discarded(Provider provider, DiscardedRecord record) {
    entries.add(new Entry(provider, record));
  }

  public synchronized List<Entry> entries() {
    return List.copyOf(entries);
  }
}
