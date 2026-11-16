package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.RepriceItinerary;
import com.flightaggregator.application.port.in.RepriceResult;
import com.flightaggregator.application.port.in.RepriceSettings;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Re-prices a chosen itinerary live (§6.3, §6.5). The legs of a trip are quoted at once, each from
 * its own provider; a leg that provider does not confirm (it no longer sells it, fails, or rejects
 * the call over its concurrency limit) is quoted from the other providers that stored an offer of
 * the same flight, the cheapest stored offer first, until one sells it. A trip is not confirmed
 * when it can no longer be sold (R5), when a leg is confirmed by no provider, or when its re-quoted
 * legs no longer connect. When the choice is not confirmed, up to {@code maxAlternatives} trips of
 * its origin and destination are tried by price, and the first confirmed is the alternative.
 *
 * <p>What is confirmed is updated in place (Q5), and the answer stands whatever {@link
 * StoreOutcome} the update answers, since its price was confirmed live: when a run has replaced the
 * inventory since the choice was read, or holds it to replace it, nothing is written. No quote
 * starts once the budget has run out; one already started is bounded by its provider's own
 * timeouts. Any other failure propagates as it was thrown: of a store, or a provider's failure of
 * another kind than {@link ProviderUnavailableException} or {@link RejectedExecutionException}.
 */
public final class RepriceItineraryService implements RepriceItinerary {

  private final ItineraryReader reader;
  private final OfferStore offers;
  private final Map<Provider, FlightProvider> providers;
  private final ItineraryRepository repository;
  private final Clock clock;
  private final RepriceSettings settings;
  private final ThreadFactory threads;

  public RepriceItineraryService(
      ItineraryReader reader,
      OfferStore offers,
      List<FlightProvider> providers,
      ItineraryRepository repository,
      Clock clock,
      RepriceSettings settings,
      ThreadFactory threads) {
    this.reader = reader;
    this.offers = offers;
    this.providers =
        providers.stream()
            .collect(Collectors.toUnmodifiableMap(FlightProvider::provider, Function.identity()));
    this.repository = repository;
    this.clock = clock;
    this.settings = settings;
    this.threads = threads;
  }

  @Override
  public RepriceResult reprice(ItineraryId id) {
    Optional<Itinerary> found = reader.byId(id);
    if (found.isEmpty()) {
      return new RepriceResult.NotFound();
    }
    Itinerary choice = found.get();
    Instant startedAt = clock.now();
    Optional<Itinerary> confirmed = confirm(choice, startedAt);
    if (confirmed.isPresent()) {
      Itinerary requoted = confirmed.get();
      repository.updateInPlace(requoted);
      return requoted.price().compareTo(choice.price()) == 0
          ? new RepriceResult.Confirmed(requoted)
          : new RepriceResult.PriceChanged(requoted, choice.price());
    }
    Optional<Itinerary> alternative =
        alternativesTo(choice, startedAt).stream()
            .map(itinerary -> confirm(itinerary, startedAt))
            .flatMap(Optional::stream)
            .findFirst();
    if (alternative.isPresent()) {
      repository.updateInPlace(alternative.get());
      return new RepriceResult.Alternative(alternative.get());
    }
    return new RepriceResult.Unconfirmable();
  }

  /**
   * Up to {@code maxAlternatives} itineraries of the choice's origin and destination, the cheapest
   * first, the choice left out.
   */
  private List<Itinerary> alternativesTo(Itinerary choice, Instant startedAt) {
    SearchCriteria sameRoute =
        new SearchCriteria(
            Optional.of(choice.origin()),
            Optional.of(choice.destination()),
            Optional.empty(),
            Optional.empty(),
            SortOrder.PRICE);
    return reader
        .page(sameRoute, Optional.empty(), settings.maxAlternatives() + 1, startedAt)
        .itineraries()
        .stream()
        .filter(itinerary -> !itinerary.id().equals(choice.id()))
        .limit(settings.maxAlternatives())
        .toList();
  }

  /**
   * The itinerary re-quoted live; none when it can no longer be sold, a leg is not confirmed by any
   * provider, or its re-quoted legs no longer make a trip (R5).
   */
  private Optional<Itinerary> confirm(Itinerary itinerary, Instant startedAt) {
    if (!itinerary.isSellable(startedAt)) {
      return Optional.empty();
    }
    List<Future<Optional<Leg>>> requotes;
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threads)) {
      requotes =
          itinerary.legs().stream()
              .map(leg -> executor.submit(() -> requote(leg, startedAt)))
              .toList();
    }
    List<Optional<Leg>> quoted = requotes.stream().map(RepriceItineraryService::resultOf).toList();
    if (quoted.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Itinerary(quoted.stream().map(Optional::orElseThrow).toList()));
    } catch (IllegalArgumentException noLongerATrip) {
      return Optional.empty();
    }
  }

  /** The result of a re-quote that has ended; what it threw propagates as it was thrown. */
  private static Optional<Leg> resultOf(Future<Optional<Leg>> requote) {
    try {
      return requote.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while a leg was re-quoted", e);
    } catch (ExecutionException e) {
      switch (e.getCause()) {
        case RuntimeException failure -> throw failure;
        case Error error -> throw error;
        default -> throw new IllegalStateException("A leg's re-quote failed", e.getCause());
      }
    }
  }

  /**
   * The leg's flight as a provider sells it now: its own provider's offer, else the first offer of
   * another provider that stored one, asked in the order of their stored offers, the cheapest
   * first.
   */
  private Optional<Leg> requote(Leg leg, Instant startedAt) {
    return quote(leg.provider(), leg.key(), startedAt)
        .or(() -> quoteFromAnotherProvider(leg, startedAt));
  }

  private Optional<Leg> quoteFromAnotherProvider(Leg leg, Instant startedAt) {
    return offers.offersOf(leg.key()).stream()
        .map(Leg::provider)
        .filter(provider -> provider != leg.provider())
        .map(provider -> quote(provider, leg.key(), startedAt))
        .flatMap(Optional::stream)
        .findFirst();
  }

  /**
   * The provider's offer of the flight now; none when it no longer sells it, fails or rejects the
   * call, or when the re-price's budget has run out, so that no quote starts after it.
   */
  private Optional<Leg> quote(Provider provider, LegKey flight, Instant startedAt) {
    if (!clock.now().isBefore(startedAt.plus(settings.budget()))) {
      return Optional.empty();
    }
    try {
      return providers.get(provider).quote(flight);
    } catch (ProviderUnavailableException | RejectedExecutionException unconfirmed) {
      return Optional.empty();
    }
  }
}
