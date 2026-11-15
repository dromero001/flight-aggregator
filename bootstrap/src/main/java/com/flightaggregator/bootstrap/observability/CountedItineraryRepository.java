package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts every update in place of a re-price as {@code reprice.store{outcome}} (§6.10), under the
 * {@link StoreOutcome} it answered: {@code STORED}, {@code GONE} or {@code BUSY}. An update skipped
 * while an ingestion run or a stale-route refresh holds the inventory is also one {@code WARN} line
 * naming the itinerary, with no price; the re-price still answers its live result. The outcome
 * passes through unchanged, and a load or a refresh passes through uncounted, since ingestion has
 * its own meters and the background refresh logs its own report.
 */
public final class CountedItineraryRepository implements ItineraryRepository {

  private static final Logger LOG = LoggerFactory.getLogger(CountedItineraryRepository.class);

  private final ItineraryRepository repository;
  private final MeterRegistry meters;

  public CountedItineraryRepository(ItineraryRepository repository, MeterRegistry meters) {
    this.repository = repository;
    this.meters = meters;
  }

  @Override
  public Publication replaceInventory(Instant now, ItineraryWriter writer) {
    return repository.replaceInventory(now, writer);
  }

  @Override
  public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
    return repository.replaceThrough(origin, writer);
  }

  @Override
  public StoreOutcome updateInPlace(Itinerary repriced) {
    StoreOutcome outcome = repository.updateInPlace(repriced);
    meters.counter("reprice.store", "outcome", outcome.name()).increment();
    if (outcome == StoreOutcome.BUSY) {
      LOG.atWarn()
          .addKeyValue("itinerary", repriced.id().value())
          .log("Skipped storing a re-price: an ingestion run or a refresh holds the inventory");
    }
    return outcome;
  }
}
