package com.flightaggregator.application.service;

import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every provider downloaded at once, and each download checked, as a run and a refresh both do
 * (§6.5). A download's discarded records are reported, and so is each leg with an airport outside
 * the catalogue. A download that arrived whole hands its usable legs over; one that failed, or that
 * left no usable leg ({@code EMPTY_DOWNLOAD}), hands nothing, so the provider's stored offers are
 * kept.
 */
final class ProviderDownloads {

  private final List<FlightProvider> providers;
  private final AirportCatalogue catalogue;
  private final DiscardedRecords discardedRecords;
  private final ThreadFactory threads;

  /**
   * The providers are downloaded and checked in {@code Provider} order; two of one name fail. Each
   * download runs on a thread of {@code threads}, created as it is submitted.
   */
  ProviderDownloads(
      List<FlightProvider> providers,
      AirportCatalogue catalogue,
      DiscardedRecords discardedRecords,
      ThreadFactory threads) {
    Set<Provider> seen = EnumSet.noneOf(Provider.class);
    for (FlightProvider provider : providers) {
      if (!seen.add(provider.provider())) {
        throw new IllegalArgumentException("Two providers named " + provider.provider());
      }
    }
    this.providers =
        providers.stream().sorted(Comparator.comparing(FlightProvider::provider)).toList();
    this.catalogue = catalogue;
    this.discardedRecords = discardedRecords;
    this.threads = threads;
  }

  /**
   * Downloads every provider at once with {@code download}, then checks each download in {@code
   * Provider} order, on the caller's thread, handing each usable one's legs, in page order, to
   * {@code usable}.
   */
  List<CheckedDownload> downloadEach(
      Function<FlightProvider, ProviderDownload> download, BiConsumer<Provider, List<Leg>> usable) {
    List<Future<Outcome>> downloads = new ArrayList<>();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threads)) {
      for (FlightProvider provider : providers) {
        downloads.add(executor.submit(() -> download(provider, download)));
      }
    }
    Set<Airport> airports = catalogue.airports();
    List<CheckedDownload> checked = new ArrayList<>();
    for (Future<Outcome> future : downloads) {
      checked.add(
          switch (join(future)) {
            case Outcome.Downloaded(var provider, var whole) ->
                check(provider, whole, airports, usable);
            case Outcome.Failed(var provider, var reason, var cause) ->
                new CheckedDownload(provider, Optional.of(reason), Optional.of(cause), 0, Map.of());
          });
    }
    return checked;
  }

  /** Reports one offer of a provider that gave its flight two arrivals. */
  void discardConflicting(Leg conflicting) {
    discardedRecords.discarded(
        conflicting.provider(),
        new DiscardedRecord(DiscardReason.CONFLICTING, conflicting.key().toString()));
  }

  /**
   * Hands over the download's legs that the catalogue knows, and discards the others. A download
   * left with no leg is a failed one: it hands nothing over.
   */
  private CheckedDownload check(
      Provider provider,
      ProviderDownload download,
      Set<Airport> airports,
      BiConsumer<Provider, List<Leg>> usable) {
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
    int fetched = download.legs().size();
    if (inCatalogue.isEmpty()) {
      return new CheckedDownload(
          provider,
          Optional.of(FailureReason.EMPTY_DOWNLOAD),
          Optional.empty(),
          fetched,
          discardedByReason);
    }
    usable.accept(provider, inCatalogue);
    return new CheckedDownload(
        provider, Optional.empty(), Optional.empty(), fetched, discardedByReason);
  }

  private void discard(
      Provider provider, DiscardedRecord record, Map<DiscardReason, Integer> byReason) {
    discardedRecords.discarded(provider, record);
    byReason.merge(record.reason(), 1, Integer::sum);
  }

  private static Outcome download(
      FlightProvider provider, Function<FlightProvider, ProviderDownload> download) {
    try {
      return new Outcome.Downloaded(provider.provider(), download.apply(provider));
    } catch (ProviderUnavailableException e) {
      return new Outcome.Failed(provider.provider(), e.reason(), e);
    } catch (RejectedExecutionException e) {
      return new Outcome.Failed(provider.provider(), FailureReason.REJECTED, e);
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
      // The handler catches every runtime exception, so an error is what can end up here, and it is
      // the caller's own; anything else would be a bug of the handler.
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("A provider download escaped its handler", e.getCause());
    }
  }

  /**
   * What one provider's download did: arrived whole with usable legs, or failed so that its stored
   * offers are kept; and what it fetched and discarded. {@code cause} is the exception of a failed
   * download; one that left no usable leg ({@code EMPTY_DOWNLOAD}) has none.
   */
  record CheckedDownload(
      Provider provider,
      Optional<FailureReason> failure,
      Optional<Throwable> cause,
      int fetched,
      Map<DiscardReason, Integer> discarded) {

    boolean fresh() {
      return failure.isEmpty();
    }
  }

  /** One provider's download: what it returned, or why it failed. */
  private sealed interface Outcome {
    record Downloaded(Provider provider, ProviderDownload download) implements Outcome {}

    record Failed(Provider provider, FailureReason reason, Throwable cause) implements Outcome {}
  }
}
