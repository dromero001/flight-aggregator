package com.flightaggregator.application.port.in;

/**
 * Where the next page of a search starts: the generation the page was read from, so a page of
 * another generation can be flagged (§6.3, Q1), its sort order, and its last row's position.
 */
public record PageCursor(long generation, SortOrder sort, SeekPosition position) {}
