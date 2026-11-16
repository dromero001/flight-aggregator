package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.in.RefreshStaleRoute;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.service.ProviderDownloads.CheckedDownload;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Deduplication;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryBuilder;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegDeduplicator;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;

/**
 * The stale-route refresh (§4.1, §6.4; plan 5 Q2-B). It downloads one origin from every provider at
 * once, each download checked as a run checks its own (§6.5), and swaps each fresh provider's
 * stored offers from the origin for the ones it has just downloaded, keeping a failed provider's.
 * It then deduplicates every offer and builds every itinerary as a run does, and the repository
 * rewrites, in the published inventory, those with a leg departing from the origin, as the next
 * generation. The stored offers are not written. The origin is marked refreshed, at the refresh's
 * start, once the rewrite has returned. When no provider's download is fresh, nothing is written
 * and the origin is not marked, so a later search asks again once its claim expires; a write that
 * fails propagates, for the caller to report.
 */
public final class RefreshStaleRouteService implements RefreshStaleRoute {

  private final ProviderDownloads downloads;
  private final OfferStore store;
  private final ItineraryRepository repository;
  private final RefreshCoordination coordination;
  private final Clock clock;

  public RefreshStaleRouteService(
      List<FlightProvider> providers,
      OfferStore store,
      AirportCatalogue catalogue,
      DiscardedRecords discardedRecords,
      ItineraryRepository repository,
      RefreshCoordination coordination,
      Clock clock,
      ThreadFactory threads) {
    this.downloads = new ProviderDownloads(providers, catalogue, discardedRecords, threads);
    this.store = store;
    this.repository = repository;
    this.coordination = coordination;
    this.clock = clock;
  }

  @Override
  public RefreshReport refresh(Airport origin) {
    Instant now = clock.now();
    Map<Provider, List<Leg>> fresh = new EnumMap<>(Provider.class);
    List<CheckedDownload> downloaded =
        downloads.downloadEach(provider -> provider.downloadFrom(origin), fresh::put);
    Map<Provider, ProviderReport.Status> statuses = statusesOf(downloaded);
    if (fresh.isEmpty()) {
      return new RefreshReport(origin, RefreshReport.Outcome.NOTHING_FRESH, 0, 0, statuses);
    }
    Deduplication deduplication =
        LegDeduplicator.deduplicate(offersWith(fresh, downloaded, origin));
    // Reported by the download that brought them, as in a run: here, the origin's fresh offers.
    deduplication.conflicting().stream()
        .filter(
            conflicting ->
                fresh.containsKey(conflicting.provider()) && conflicting.origin().equals(origin))
        .forEach(downloads::discardConflicting);
    Publication publication =
        repository.replaceThrough(
            origin,
            sink ->
                ItineraryBuilder.build(deduplication, now)
                    .filter(itinerary -> hasALegFrom(itinerary, origin))
                    .forEach(sink));
    coordination.refreshed(origin, now);
    return new RefreshReport(
        origin,
        RefreshReport.Outcome.REFRESHED,
        publication.generation(),
        publication.itineraries(),
        statuses);
  }

  /**
   * Every provider's stored offers, each fresh provider's offers from the origin swapped for those
   * it has just downloaded. The swap is in memory only: the next run downloads everything again.
   */
  private List<Leg> offersWith(
      Map<Provider, List<Leg>> fresh, List<CheckedDownload> downloaded, Airport origin) {
    List<Leg> offers = new ArrayList<>();
    for (CheckedDownload download : downloaded) {
      List<Leg> stored = store.offers(download.provider());
      if (download.fresh()) {
        stored.stream().filter(offer -> !offer.origin().equals(origin)).forEach(offers::add);
        offers.addAll(fresh.get(download.provider()));
      } else {
        offers.addAll(stored);
      }
    }
    return offers;
  }

  private static Map<Provider, ProviderReport.Status> statusesOf(List<CheckedDownload> downloaded) {
    Map<Provider, ProviderReport.Status> statuses = new EnumMap<>(Provider.class);
    for (CheckedDownload download : downloaded) {
      statuses.put(
          download.provider(),
          download.fresh() ? ProviderReport.Status.FRESH : ProviderReport.Status.KEPT);
    }
    return statuses;
  }

  /** The rows a refresh of the origin rewrites: those with a leg departing from it. */
  private static boolean hasALegFrom(Itinerary itinerary, Airport origin) {
    return itinerary.legs().stream().anyMatch(leg -> leg.origin().equals(origin));
  }
}
