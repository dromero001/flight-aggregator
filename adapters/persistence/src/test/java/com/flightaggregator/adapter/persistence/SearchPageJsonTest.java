package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.json.JsonFactory;

class SearchPageJsonTest {

  /**
   * A document this class did not write is refused, never read as a page that is not the one
   * cached. The round trip is {@code ValkeySearchCacheTest.roundTripsAPage}.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{\"itineraries\":[],\"previous\":null,\"inventoryChanged\":false}",
        "{\"itineraries\":[],\"next\":null,\"inventoryChanged\":\"no\"}",
        "{\"itineraries\":[],\"next\":null,\"inventoryChanged\":false}{}",
        "{\"itineraries\":[[]],\"next\":null,\"inventoryChanged\":false}",
        "{\"itineraries\":[],\"next\":{\"generation\":1,\"sort\":\"CHEAPEST\",\"price\":\"1.00\","
            + "\"departure\":\"2026-11-20T10:00:00Z\",\"duration\":\"PT2H\","
            + "\"id\":\"9b2a5a4e-0d1b-3c55-8f43-2f0a8b6c1d7e\"},\"inventoryChanged\":false}"
      })
  void refusesADocumentThatIsNotAPage(String document) {
    assertThatThrownBy(() -> SearchPageJson.read(document, new JsonFactory()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("the cached document is not a search page");
  }
}
