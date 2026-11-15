package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.port.out.SearchKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A search cache in a map: it keeps every page put, for as long as the test runs. */
public final class InMemorySearchCache implements SearchCache {

  private final Map<SearchKey, SearchPage> pages = new HashMap<>();

  @Override
  public Optional<SearchPage> get(SearchKey key) {
    return Optional.ofNullable(pages.get(key));
  }

  @Override
  public void put(SearchKey key, SearchPage page) {
    pages.put(key, page);
  }

  /** Every page cached, by its key. */
  public Map<SearchKey, SearchPage> pages() {
    return Map.copyOf(pages);
  }
}
