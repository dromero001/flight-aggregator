package com.flightaggregator.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.domain.Itinerary;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoredPageTest {

  @Test
  void copiesTheItineraries() {
    List<Itinerary> itineraries = new ArrayList<>();
    StoredPage page = new StoredPage(1, itineraries);

    itineraries.add(null);

    assertThat(page.itineraries()).isEmpty();
  }
}
