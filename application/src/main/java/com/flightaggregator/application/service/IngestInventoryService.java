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
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Downloads every provider and stores what arrived whole. */
public final class IngestInventoryService implements IngestInventory {

  private final List<FlightProvider> providers;
  private final OfferStore store;
  private final AirportCatalogue catalogue;
  private final DiscardedRecords discardedRecords;
  private final Clock clock;

  public IngestInventoryService(
      List<FlightProvider> providers,
      OfferStore store,
      AirportCatalogue catalogue,
      DiscardedRecords discardedRecords,
      Clock clock) {
    this.providers =
        providers.stream().sorted(Comparator.comparing(FlightProvider::provider)).toList();
    this.store = store;
    this.catalogue = catalogue;
    this.discardedRecords = discardedRecords;
    this.clock = clock;
  }

  @Override
  public IngestionReport ingest() {
    Instant startedAt = clock.now();
    List<Future<Outcome>> downloads = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (FlightProvider provider : providers) {
        downloads.add(executor.submit(() -> download(provider)));
      }
    }
    Set<Airport> airports = catalogue.airports();
    List<ProviderReport> reports = new ArrayList<>();
    for (Future<Outcome> future : downloads) {
      reports.add(
          switch (join(future)) {
            case Outcome.Downloaded(var provider, var download) ->
                fresh(provider, download, airports);
            case Outcome.Failed(var provider, var reason, var cause) ->
                kept(provider, reason, cause);
          });
    }
    return new IngestionReport(
        startedAt, Duration.ZERO, IngestionReport.Outcome.NO_OFFERS, 0, 0, reports);
  }

  private ProviderReport fresh(
      Provider provider, ProviderDownload download, Set<Airport> airports) {
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
    return new ProviderReport(
        provider,
        ProviderReport.Status.FRESH,
        Optional.empty(),
        Optional.empty(),
        download.legs().size(),
        discardedByReason,
        inCatalogue.size(),
        0);
  }

  private void discard(
      Provider provider, DiscardedRecord record, Map<DiscardReason, Integer> byReason) {
    discardedRecords.discarded(provider, record);
    byReason.merge(record.reason(), 1, Integer::sum);
  }

  private ProviderReport kept(Provider provider, FailureReason reason, Throwable cause) {
    return new ProviderReport(
        provider,
        ProviderReport.Status.KEPT,
        Optional.of(reason),
        Optional.ofNullable(cause),
        0,
        Map.of(),
        store.offers(provider).size(),
        0);
  }

  /** One provider's outcome: its download, or the reason it failed. */
  private sealed interface Outcome {
    record Downloaded(Provider provider, ProviderDownload download) implements Outcome {}

    record Failed(Provider provider, FailureReason reason, Throwable cause) implements Outcome {}
  }

  private static Outcome download(FlightProvider provider) {
    try {
      return new Outcome.Downloaded(provider.provider(), provider.download());
    } catch (ProviderUnavailableException e) {
      return new Outcome.Failed(provider.provider(), e.reason(), e.getCause());
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
}
