package com.flightaggregator.adapter.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.flightaggregator.application.port.in.SearchPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of results (§5.4): the cursor of the next page, none on the last one, and whether the
 * inventory changed since the cursor this page followed was issued (Q1-A, "results updated,
 * reload"). No total count: it would cost a count over the whole inventory.
 */
record SearchResponse(
    @Schema(requiredMode = REQUIRED) List<ItineraryResponse> itineraries,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        String nextCursor,
    @Schema(requiredMode = REQUIRED) boolean inventoryChanged) {

  static SearchResponse of(SearchPage page) {
    return new SearchResponse(
        page.itineraries().stream().map(ItineraryResponse::of).toList(),
        page.next().map(CursorCodec::encode).orElse(null),
        page.inventoryChanged());
  }
}
