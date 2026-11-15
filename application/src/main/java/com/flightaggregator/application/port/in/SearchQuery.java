package com.flightaggregator.application.port.in;

import java.util.Optional;

/**
 * One page of a search: its criteria, how many itineraries the page holds at most, and the cursor
 * of the page it follows, none for the first page.
 */
public record SearchQuery(SearchCriteria criteria, int limit, Optional<PageCursor> cursor) {}
