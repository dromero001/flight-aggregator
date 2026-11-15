package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.IngestInventory;
import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Deduplication;
import com.flightaggregator.domain.ItineraryBuilder;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegDeduplicator;
import com.flightaggregator.domain.Provider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One ingestion run (§5.1, §6.5): downloads every provider in parallel, stores each download that
 * arrived whole and keeps the last good offers of each one that did not, deduplicates every stored
 * offer, then streams the itineraries they build into the repository, which publishes them as one
 * generation. Each provider's report is built once, after the deduplication.
 */
public final class IngestInventoryService implements IngestInventory {

  private final List<FlightProvider> providers;
  private final OfferStore store;
  private final AirportCatalogue catalogue;
  private final DiscardedRecords discardedRecords;
  private final ItineraryRepository repository;
  private final Clock clock;
  private final long maxItineraries;

  public IngestInventoryService(
      List<FlightProvider> providers,
      OfferStore store,
      AirportCatalogue catalogue,
      DiscardedRecords discardedRecords,
      ItineraryRepository repository,
      Clock clock,
      long maxItineraries) {
    Set<Provider> seen = EnumSet.noneOf(Provider.class);
    for (FlightProvider provider : providers) {
      if (!seen.add(provider.provider())) {
        throw new IllegalArgumentException("Two providers named " + provider.provider());
      }
    }
    this.providers =
        providers.stream().sorted(Comparator.comparing(FlightProvider::provider)).toList();
    this.store = store;
    this.catalogue = catalogue;
    this.discardedRecords = discardedRecords;
    this.repository = repository;
    this.clock = clock;
    this.maxItineraries = maxItineraries;
  }

  @Override
  public IngestionReport ingest() {
    Instant startedAt = clock.now();
    List<ProviderRun> runs = downloadAndStore();
    Map<Provider, List<Leg>> stored = storedOffers();
    List<Leg> offers = stored.values().stream().flatMap(List::stream).toList();
    Deduplication deduplication = LegDeduplicator.deduplicate(offers);
    deduplication.conflicting().forEach(this::discardConflicting);
    List<ProviderReport> reports =
        runs.stream().map(run -> run.report(stored.get(run.provider()), deduplication)).toList();
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

  /** Downloads every provider at once and stores what arrived whole, in {@code Provider} order. */
  private List<ProviderRun> downloadAndStore() {
    List<Future<Outcome>> downloads = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (FlightProvider provider : providers) {
        downloads.add(executor.submit(() -> download(provider)));
      }
    }
    Set<Airport> airports = catalogue.airports();
    List<ProviderRun> runs = new ArrayList<>();
    for (Future<Outcome> future : downloads) {
      runs.add(
          switch (join(future)) {
            case Outcome.Downloaded(var provider, var download) ->
                store(provider, download, airports);
            case Outcome.Failed(var provider, var reason, var cause) ->
                new ProviderRun(provider, Optional.of(reason), Optional.of(cause), 0, Map.of());
          });
    }
    return runs;
  }

  /** Each provider's stored offers after the downloads, fresh or kept, read once. */
  private Map<Provider, List<Leg>> storedOffers() {
    Map<Provider, List<Leg>> stored = new EnumMap<>(Provider.class);
    for (FlightProvider provider : providers) {
      stored.put(provider.provider(), store.offers(provider.provider()));
    }
    return stored;
  }

  private void discardConflicting(Leg conflicting) {
    discardedRecords.discarded(
        conflicting.provider(),
        new DiscardedRecord(DiscardReason.CONFLICTING, conflicting.key().toString()));
  }

  /** The report of a run that ends now. */
  private IngestionReport report(
      Instant startedAt,
      IngestionReport.Outcome outcome,
      Optional<Publication> publication,
      List<ProviderReport> providerReports) {
    return new IngestionReport(
        startedAt, Duration.between(startedAt, clock.now()), outcome, publication, providerReports);
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

  /** Stores the download's legs that the catalogue knows, and discards the others. */
  private ProviderRun store(Provider provider, ProviderDownload download, Set<Airport> airports) {
    Map<DiscardReason, Integer> discardedByReason = new EnumMap<>(DiscardReason.class);
    for (DiscardedRecord record : download.discarded()) {
      discard(provider, record, discardedByReason);
    }
    List<Leg> inCatalogue = new ArrayList<>();
    for (Leg leg : download.legs()) {
      if (airports.contains(leg.origin()) && airports.contains(leg.destination())) {
        inCatalogue.add(leg);
      } else {
        discard(
            provider,
            new DiscardedRecord(DiscardReason.UNKNOWN_AIRPORT, leg.key().toString()),
            discardedByReason);
      }
    }
    store.replace(provider, inCatalogue);
    return new ProviderRun(
        provider, Optional.empty(), Optional.empty(), download.legs().size(), discardedByReason);
  }

  private void discard(
      Provider provider, DiscardedRecord record, Map<DiscardReason, Integer> byReason) {
    discardedRecords.discarded(provider, record);
    byReason.merge(record.reason(), 1, Integer::sum);
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

  private static Outcome download(FlightProvider provider) {
    try {
      return new Outcome.Downloaded(provider.provider(), provider.download());
    } catch (ProviderUnavailableException e) {
      return new Outcome.Failed(provider.provider(), e.reason(), e);
    } catch (RuntimeException e) {
      return new Outcome.Failed(provider.provider(), FailureReason.UNEXPECTED, e);
    }
  }

  private static Outcome join(Future<Outcome> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a provider", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("A provider download escaped its handler", e.getCause());
    }
  }

  /** One provider's download: what it returned, or why it failed. */
  private sealed interface Outcome {
    record Downloaded(Provider provider, ProviderDownload download) implements Outcome {}

    record Failed(Provider provider, FailureReason reason, Throwable cause) implements Outcome {}
  }

  /**
   * What one provider's download did in this run: stored as its new offers, or failed so that its
   * last good ones are kept; and what it fetched and discarded.
   */
  private record ProviderRun(
      Provider provider,
      Optional<FailureReason> failure,
      Optional<Throwable> cause,
      int fetched,
      Map<DiscardReason, Integer> discarded) {

    /**
     * Its report, built once its stored offers were deduplicated with the others': the conflicting
     * offers take no part, and those the deduplication left out are lost to it.
     */
    ProviderReport report(List<Leg> stored, Deduplication deduplication) {
      int conflicting = ofThisProvider(deduplication.conflicting());
      int chosen = ofThisProvider(deduplication.legs());
      return new ProviderReport(
          provider,
          failure,
          cause,
          fetched,
          withConflicting(discarded, conflicting),
          stored.size() - conflicting,
          stored.size() - conflicting - chosen,
          newestQuote(stored));
    }

    private int ofThisProvider(List<Leg> legs) {
      return (int) legs.stream().filter(leg -> leg.provider() == provider).count();
    }
  }
}
