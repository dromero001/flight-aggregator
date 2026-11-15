package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderRun;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.service.ProviderDownloads.CheckedDownload;
import com.flightaggregator.domain.Deduplication;
import com.flightaggregator.domain.ItineraryBuilder;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegDeduplicator;
import com.flightaggregator.domain.Provider;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One ingestion run (§5.1, §6.5): downloads every provider in parallel, stores each download that
 * arrived whole and keeps the last good offers of each one that did not, deduplicates every stored
 * offer, then streams the itineraries they build into the repository, which publishes them as one
 * generation. Each provider's report is built once, after the deduplication.
 */
public final class IngestInventoryService implements IngestInventory {

  private final ProviderDownloads downloads;
  private final OfferStore store;
  private final ItineraryRepository repository;
  private final IngestionStatusStore statuses;
  private final Clock clock;
  private final long maxItineraries;

  public IngestInventoryService(
      List<FlightProvider> providers,
      OfferStore store,
      AirportCatalogue catalogue,
      DiscardedRecords discardedRecords,
      ItineraryRepository repository,
      IngestionStatusStore statuses,
      Clock clock,
      long maxItineraries) {
    this.downloads = new ProviderDownloads(providers, catalogue, discardedRecords);
    this.store = store;
    this.repository = repository;
    this.statuses = statuses;
    this.clock = clock;
    this.maxItineraries = maxItineraries;
  }

  @Override
  public IngestionReport ingest() {
    Instant startedAt = clock.now();
    List<CheckedDownload> runs = downloads.downloadEach(FlightProvider::download, store::replace);
    Map<Provider, List<Leg>> stored = storedOffers(runs);
    List<Leg> offers = stored.values().stream().flatMap(List::stream).toList();
    Deduplication deduplication = LegDeduplicator.deduplicate(offers);
    Set<Provider> fresh = freshProviders(runs);
    // Reported once, by the run that downloaded them: a kept provider's conflicts are not re-sent.
    deduplication.conflicting().stream()
        .filter(conflicting -> fresh.contains(conflicting.provider()))
        .forEach(downloads::discardConflicting);
    List<ProviderReport> reports =
        runs.stream().map(run -> report(run, stored.get(run.provider()), deduplication)).toList();
    return recorded(publish(startedAt, offers, deduplication, reports));
  }

  /**
   * Records the run's providers for the provider-status screen (Q7-B), whatever its outcome: their
   * statuses are the last known either way. A failure to record them leaves the run as it was; the
   * report carries it, for the caller to log.
   */
  private IngestionReport recorded(IngestionReport report) {
    try {
      statuses.record(
          report.providers().stream().map(ProviderRun::of).toList(),
          report.startedAt().plus(report.duration()));
      return report;
    } catch (RuntimeException e) {
      return report.withStatusNotRecorded(e);
    }
  }

  /** Publishes the itineraries the offers build, when there are any, and reports the run. */
  private IngestionReport publish(
      Instant startedAt,
      List<Leg> offers,
      Deduplication deduplication,
      List<ProviderReport> reports) {
    if (offers.isEmpty()) {
      return report(
          startedAt, IngestionReport.Outcome.NOTHING_TO_PUBLISH, Optional.empty(), reports);
    }
    try {
      Publication publication =
          repository.replaceInventory(startedAt, writerOf(deduplication, startedAt));
      return report(
          startedAt, IngestionReport.Outcome.PUBLISHED, Optional.of(publication), reports);
    } catch (NoItineraryBuilt e) {
      return report(
          startedAt, IngestionReport.Outcome.NOTHING_TO_PUBLISH, Optional.empty(), reports);
    } catch (IngestionCeiling e) {
      return report(startedAt, IngestionReport.Outcome.CEILING_EXCEEDED, Optional.empty(), reports);
    }
  }

  /** Each provider's stored offers after the downloads, fresh or kept, read once. */
  private Map<Provider, List<Leg>> storedOffers(List<CheckedDownload> runs) {
    Map<Provider, List<Leg>> stored = new EnumMap<>(Provider.class);
    for (CheckedDownload run : runs) {
      stored.put(run.provider(), store.offers(run.provider()));
    }
    return stored;
  }

  /** The providers whose download was stored in this run. */
  private static Set<Provider> freshProviders(List<CheckedDownload> runs) {
    Set<Provider> fresh = EnumSet.noneOf(Provider.class);
    runs.stream().filter(CheckedDownload::fresh).map(CheckedDownload::provider).forEach(fresh::add);
    return fresh;
  }

  /** The report of a run that ends now. */
  private IngestionReport report(
      Instant startedAt,
      IngestionReport.Outcome outcome,
      Optional<Publication> publication,
      List<ProviderReport> providerReports) {
    return new IngestionReport(
        startedAt,
        Duration.between(startedAt, clock.now()),
        outcome,
        publication,
        providerReports,
        Optional.empty());
  }

  private ItineraryWriter writerOf(Deduplication deduplication, Instant now) {
    return sink -> {
      AtomicLong written = new AtomicLong();
      ItineraryBuilder.build(deduplication, now)
          .forEach(
              itinerary -> {
                if (written.incrementAndGet() > maxItineraries) {
                  throw new IngestionCeiling(maxItineraries);
                }
                sink.accept(itinerary);
              });
      if (written.get() == 0) {
        throw new NoItineraryBuilt();
      }
    };
  }

  /**
   * One provider's report, built once its stored offers were deduplicated with the others': the
   * conflicting offers take no part, and those the deduplication left out are lost to it. Only a
   * fresh download counts its conflicting offers as discarded.
   */
  private static ProviderReport report(
      CheckedDownload run, List<Leg> stored, Deduplication deduplication) {
    int conflicting = ofProvider(run.provider(), deduplication.conflicting());
    int chosen = ofProvider(run.provider(), deduplication.legs());
    return new ProviderReport(
        run.provider(),
        run.failure(),
        run.cause(),
        run.fetched(),
        run.fresh() ? withConflicting(run.discarded(), conflicting) : run.discarded(),
        stored.size() - conflicting,
        stored.size() - conflicting - chosen,
        newestQuote(stored));
  }

  private static int ofProvider(Provider provider, List<Leg> legs) {
    return (int) legs.stream().filter(leg -> leg.provider() == provider).count();
  }

  private static Map<DiscardReason, Integer> withConflicting(
      Map<DiscardReason, Integer> discarded, int conflicting) {
    if (conflicting == 0) {
      return discarded;
    }
    Map<DiscardReason, Integer> merged = new EnumMap<>(DiscardReason.class);
    merged.putAll(discarded);
    merged.merge(DiscardReason.CONFLICTING, conflicting, Integer::sum);
    return merged;
  }

  /** The newest quote among the offers, empty when there are none. */
  private static Optional<Instant> newestQuote(List<Leg> offers) {
    return offers.stream().map(Leg::quotedAt).max(Comparator.naturalOrder());
  }
}
