package com.flightaggregator.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.domain.Itinerary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchPageTest {

  @Test
  void copiesTheItineraries() {
    List<Itinerary> itineraries = new ArrayList<>();
    SearchPage page = new SearchPage(itineraries, Optional.empty(), false);

    itineraries.add(null);

    assertThat(page.itineraries()).isEmpty();
  }
}
