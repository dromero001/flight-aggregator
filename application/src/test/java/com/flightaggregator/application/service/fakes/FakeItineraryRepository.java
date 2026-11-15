package com.flightaggregator.application.service.fakes;

import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Itinerary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An itinerary repository that keeps what the last writer pushed, and publishes it only if the
 * writer returned normally, as the real one does.
 */
public final class FakeItineraryRepository implements ItineraryRepository {

  public static final long GENERATION = 7;
  public static final long ROWS_IN_DEFAULT_PARTITION = 2;
  public static final Instant PARTITIONED_UNTIL = Instant.parse("2026-12-28T00:00:00Z");

  private int calls;
  private List<Itinerary> published = List.of();

  @Override
  public Publication replaceInventory(Instant now, ItineraryWriter writer) {
    calls++;
    List<Itinerary> written = new ArrayList<>();
    writer.writeTo(written::add);
    published = List.copyOf(written);
    return new Publication(
        GENERATION, published.size(), ROWS_IN_DEFAULT_PARTITION, PARTITIONED_UNTIL);
  }

  public int calls() {
    return calls;
  }

  public List<Itinerary> published() {
    return published;
  }
}
