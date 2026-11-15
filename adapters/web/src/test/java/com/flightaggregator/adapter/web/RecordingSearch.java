package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.SearchItineraries;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SearchQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test double: answers the page the test sets, an empty one by default, and records every query it
 * is asked. Spring keeps one per cached context, so each test starts with {@link #reset}.
 */
final class RecordingSearch implements SearchItineraries {

  private static final SearchPage EMPTY = new SearchPage(List.of(), Optional.empty(), false);

  private final List<SearchQuery> queries = new ArrayList<>();
  private SearchPage page = EMPTY;

  void reset() {
    queries.clear();
    page = EMPTY;
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
    return page;
  }
}
