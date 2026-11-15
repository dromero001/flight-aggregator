package com.flightaggregator.application.port.out;

import com.flightaggregator.application.port.in.SearchPage;
import java.util.Optional;

/**
 * Pages of search results kept for a short time (cache-aside, §6.2). The cache may be unavailable:
 * then {@code get} finds nothing and {@code put} keeps nothing, and search still answers, only
 * slower.
 */
public interface SearchCache {

  /** The page cached under this key, if it is still there. */
  Optional<SearchPage> get(SearchKey key);

  /** Keeps the page under this key for the cache's time to live. */
  void put(SearchKey key, SearchPage page);
}
