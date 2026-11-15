package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.RepriceItinerary;
import com.flightaggregator.application.port.in.RepriceResult;
import com.flightaggregator.domain.ItineraryId;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double: answers the result the test sets, {@code NotFound} by default, and records every id
 * it is asked. Spring keeps one per cached context, so each test starts with {@link #reset}.
 */
final class RecordingReprice implements RepriceItinerary {

  private final List<ItineraryId> ids = new ArrayList<>();
  private RepriceResult result = new RepriceResult.NotFound();

  void reset() {
    ids.clear();
    result = new RepriceResult.NotFound();
  }

  void answer(RepriceResult result) {
    this.result = result;
  }

  List<ItineraryId> ids() {
    return ids;
  }

  @Override
  public RepriceResult reprice(ItineraryId id) {
    ids.add(id);
    return result;
  }
}
