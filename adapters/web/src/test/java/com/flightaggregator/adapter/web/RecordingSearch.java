package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.SearchItineraries;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test double: answers the page the test sets, an empty one by default, or throws the failure the
 * test sets, and records every query it is asked. Spring keeps one per cached context, so each test
 * starts with {@link #reset}.
 */
final class RecordingSearch implements SearchItineraries {

  private static final SearchPage EMPTY = new SearchPage(List.of(), Optional.empty(), false);

  private final List<SearchQuery> queries = new ArrayList<>();
  private SearchPage page = EMPTY;
  private Optional<RuntimeException> failure = Optional.empty();

  void reset() {
    queries.clear();
    page = EMPTY;
    failure = Optional.empty();
  }

  void fail(RuntimeException failure) {
    this.failure = Optional.of(failure);
  }

  void answer(SearchPage page) {
    this.page = page;
  }

  List<SearchQuery> queries() {
    return queries;
  }

  @Override
  public SearchPage search(SearchQuery query) {
    queries.add(query);
    failure.ifPresent(
        e -> {
          throw e;
        });
    return page;
  }
}
