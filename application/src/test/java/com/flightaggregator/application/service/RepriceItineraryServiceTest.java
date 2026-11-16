package com.flightaggregator.application.service;

import static com.flightaggregator.application.service.Legs.leg;
import static com.flightaggregator.application.service.Legs.pricedAt;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.application.port.in.RepriceResult;
import com.flightaggregator.application.port.in.RepriceSettings;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.application.service.fakes.FakeFlightProvider;
import com.flightaggregator.application.service.fakes.FakeItineraryRepository;
import com.flightaggregator.application.service.fakes.InMemoryItineraryReader;
import com.flightaggregator.application.service.fakes.InMemoryOfferStore;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RepriceItineraryServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final Instant REQUOTED_AT = Instant.parse("2026-11-20T08:00:01Z");
  private static final RepriceSettings SETTINGS = new RepriceSettings(3, Duration.ofSeconds(10));

  /** Madrid to London on IB1 from Alpha, then on to Barcelona on IB2 from Beta, 2 h later. */
  private static final Leg MAD_LHR =
      leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", Legs.QUOTED_AT);

  private static final Leg LHR_BCN =
      leg(BETA, 2, "LHR", "BCN", "2026-11-25T11:15:00Z", Legs.QUOTED_AT);

  private final InMemoryItineraryReader reader = new InMemoryItineraryReader();
  private final InMemoryOfferStore offers = new InMemoryOfferStore();
  private final FakeItineraryRepository repository = new FakeItineraryRepository();

  /** What each provider sells now, by flight. */
  private final Map<Provider, Map<LegKey, Leg>> selling = new EnumMap<>(Provider.class);

  /** What each failing provider throws at every quote instead. */
  private final Map<Provider, RuntimeException> failing = new EnumMap<>(Provider.class);

  /** Every quote asked; the legs of a trip are quoted at once, so in no set order. */
  private final Queue<Quote> asked = new ConcurrentLinkedQueue<>();

  /** When each quote started, on the service's clock. */
  private final Queue<Instant> quoteStarts = new ConcurrentLinkedQueue<>();

  private Clock clock = () -> NOW;
  private RepriceSettings settings = SETTINGS;

  /** What each quote does after it has started and before it answers: nothing by default. */
  private QuoteWork eachQuote = start -> {};

  private record Quote(Provider provider, LegKey flight) {}

  private interface QuoteWork {
    void run(Instant start) throws InterruptedException, TimeoutException, BrokenBarrierException;
  }

  private RepriceItineraryService service() {
    List<FlightProvider> providers =
        Stream.of(Provider.values())
            .<FlightProvider>map(
                provider -> FakeFlightProvider.quoting(provider, flight -> quote(provider, flight)))
            .toList();
    return new RepriceItineraryService(reader, offers, providers, repository, clock, settings);
  }

  private Optional<Leg> quote(Provider provider, LegKey flight) {
    Instant start = clock.now();
    quoteStarts.add(start);
    asked.add(new Quote(provider, flight));
    try {
      eachQuote.run(start);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted in a quote", e);
    } catch (TimeoutException | BrokenBarrierException e) {
      throw new IllegalStateException("A quote waited in vain for the other legs of its trip", e);
    }
    RuntimeException failure = failing.get(provider);
    if (failure != null) {
      throw failure;
    }
    return Optional.ofNullable(selling.getOrDefault(provider, Map.of()).get(flight));
  }

  /** The provider times out on every quote. */
  private void down(Provider provider) {
    failing.put(provider, new ProviderUnavailableException(provider, FailureReason.TIMEOUT, null));
  }

  /** The provider of the offer now sells its flight as the offer says. */
  private void sells(Leg offer) {
    selling.computeIfAbsent(offer.provider(), provider -> new HashMap<>()).put(offer.key(), offer);
  }

  private static Itinerary trip(Leg... legs) {
    return new Itinerary(List.of(legs));
  }

  /** A direct flight {@code IB<number>} from Madrid to Barcelona, stored at that price. */
  private static Itinerary toBarcelona(Provider provider, int number, String euros) {
    return trip(
        pricedAt(
            leg(provider, number, "MAD", "BCN", "2026-11-25T09:15:00Z", Legs.QUOTED_AT), euros));
  }

  /** The provider's offer of the leg's flight now, at that price before the markup. */
  private static Leg offer(Leg leg, Provider provider, String euros) {
    return landingAt(leg, leg.arrival(), provider, euros);
  }

  /** The same, landing at another time: the flight's key does not hold its arrival. */
  private static Leg landingAt(Leg leg, OffsetDateTime arrival, Provider provider, String euros) {
    return new Leg(
        leg.carrier(),
        leg.flightNumber(),
        leg.origin(),
        leg.destination(),
        leg.departure(),
        arrival,
        Money.euros(euros),
        provider,
        REQUOTED_AT);
  }

  @Test
  void answersNotFoundForAnUnknownId() {
    reader.with(trip(MAD_LHR));

    RepriceResult result = service().reprice(trip(MAD_LHR, LHR_BCN).id());

    assertThat(result).isEqualTo(new RepriceResult.NotFound());
  }

  @Test
  void confirmsAnUnchangedPrice() {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    reader.with(choice);
    Leg madLhr = offer(MAD_LHR, ALPHA, "100.00");
    Leg lhrBcn = offer(LHR_BCN, BETA, "100.00");
    sells(madLhr);
    sells(lhrBcn);

    RepriceResult result = service().reprice(choice.id());

    Itinerary requoted = trip(madLhr, lhrBcn);
    assertThat(result).isEqualTo(new RepriceResult.Confirmed(requoted));
    assertThat(requoted.quotedAt()).isEqualTo(REQUOTED_AT);
    assertThat(repository.updated()).containsExactly(requoted);
  }

  @Test
  void reportsAChangedPriceAndStoresIt() {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    reader.with(choice);
    Leg madLhr = offer(MAD_LHR, ALPHA, "120.00");
    Leg lhrBcn = offer(LHR_BCN, BETA, "100.00");
    sells(madLhr);
    sells(lhrBcn);

    RepriceResult result = service().reprice(choice.id());

    Itinerary requoted = trip(madLhr, lhrBcn);
    assertThat(result).isEqualTo(new RepriceResult.PriceChanged(requoted, Money.euros("210.00")));
    assertThat(requoted.price()).isEqualTo(Money.euros("231.00"));
    assertThat(repository.updated()).containsExactly(requoted);
  }

  /**
   * The price was confirmed live, so the answer stands whatever became of its store: written, gone
   * since a run replaced the inventory, or skipped while a run holds it.
   */
  @ParameterizedTest
  @EnumSource(StoreOutcome.class)
  void answersTheConfirmedPriceWhateverBecameOfItsStore(StoreOutcome stored) {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    reader.with(choice);
    Leg madLhr = offer(MAD_LHR, ALPHA, "100.00");
    Leg lhrBcn = offer(LHR_BCN, BETA, "100.00");
    sells(madLhr);
    sells(lhrBcn);
    repository.answers(stored);

    RepriceResult result = service().reprice(choice.id());

    assertThat(result).isEqualTo(new RepriceResult.Confirmed(trip(madLhr, lhrBcn)));
    assertThat(repository.updated()).containsExactly(trip(madLhr, lhrBcn));
  }

  /** Why Beta, the provider of the second leg, does not confirm it. */
  enum BetaAnswer {
    UNAVAILABLE,
    REJECTED,
    NO_LONGER_SOLD
  }

  /**
   * Beta's leg is quoted from the other providers that stored an offer of the same flight, the
   * cheapest stored offer first, until one sells it now. Beta's own stored offer, the cheapest, is
   * not quoted again, and Alpha, whose stored offer is dearer than Gamma's, is not asked at all.
   */
  @ParameterizedTest
  @EnumSource(BetaAnswer.class)
  void confirmsALegWithAnotherProvidersOfferOfTheSameFlight(BetaAnswer beta) {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    reader.with(choice);
    offers
        .with(ALPHA, List.of(MAD_LHR, offer(LHR_BCN, ALPHA, "110.00")))
        .with(BETA, List.of(LHR_BCN))
        .with(GAMMA, List.of(offer(LHR_BCN, GAMMA, "105.00")));
    Leg madLhr = offer(MAD_LHR, ALPHA, "100.00");
    Leg byGamma = offer(LHR_BCN, GAMMA, "95.00");
    sells(madLhr);
    sells(offer(LHR_BCN, ALPHA, "90.00"));
    sells(byGamma);
    switch (beta) {
      case UNAVAILABLE -> down(BETA);
      case REJECTED -> failing.put(BETA, new RejectedExecutionException("over Beta's limit"));
      case NO_LONGER_SOLD -> {
        // Beta answers, but sells nothing.
      }
    }

    RepriceResult result = service().reprice(choice.id());

    Itinerary requoted = trip(madLhr, byGamma);
    assertThat(result).isEqualTo(new RepriceResult.PriceChanged(requoted, Money.euros("210.00")));
    assertThat(repository.updated()).containsExactly(requoted);
    assertThat(asked)
        .containsExactlyInAnyOrder(
            new Quote(ALPHA, MAD_LHR.key()),
            new Quote(BETA, LHR_BCN.key()),
            new Quote(GAMMA, LHR_BCN.key()));
  }

  /**
   * R5: Alpha's re-quote moves the first leg's arrival to 30 minutes before the second leg departs,
   * under the 45-minute connection, so the re-quoted legs are no trip: nothing is confirmed or
   * stored.
   */
  @Test
  void treatsRequotedLegsThatNoLongerConnectAsUnconfirmed() {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    reader.with(choice);
    sells(landingAt(MAD_LHR, LHR_BCN.departure().minusMinutes(30), ALPHA, "100.00"));
    sells(offer(LHR_BCN, BETA, "100.00"));

    RepriceResult result = service().reprice(choice.id());

    assertThat(result).isEqualTo(new RepriceResult.Unconfirmable());
    assertThat(repository.updated()).isEmpty();
  }

  /**
   * Beta is down and no other provider stored its leg, so the choice cannot be confirmed. The
   * route's itineraries are tried by price: the cheapest is no longer sold, the next one is, and
   * the dearer one after it is never quoted. Cheaper trips to another destination, or from another
   * origin, both sold, are not the route's.
   */
  @Test
  void offersTheNextCheapestConfirmableItinerary() {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    Itinerary noLongerSold = toBarcelona(ALPHA, 10, "150.00");
    Itinerary next = toBarcelona(GAMMA, 11, "180.00");
    Itinerary dearer = toBarcelona(ALPHA, 12, "190.00");
    Itinerary toLondon = trip(MAD_LHR);
    Leg lhrBcn = leg(GAMMA, 20, "LHR", "BCN", "2026-11-25T09:15:00Z", Legs.QUOTED_AT);
    Itinerary fromLondon = trip(lhrBcn);
    reader.with(choice, dearer, toLondon, fromLondon, next, noLongerSold);
    sells(offer(lhrBcn, GAMMA, "100.00"));
    down(BETA);
    sells(offer(MAD_LHR, ALPHA, "100.00"));
    Leg confirmed = offer(next.legs().getFirst(), GAMMA, "185.00");
    sells(confirmed);
    sells(offer(dearer.legs().getFirst(), ALPHA, "190.00"));

    RepriceResult result = service().reprice(choice.id());

    Itinerary alternative = trip(confirmed);
    assertThat(result).isEqualTo(new RepriceResult.Alternative(alternative));
    assertThat(repository.updated()).containsExactly(alternative);
    assertThat(asked).doesNotContain(new Quote(ALPHA, dearer.legs().getFirst().key()));
  }

  /**
   * Q4, R5: a choice whose first leg left before now is never quoted, though its provider still
   * lists it; the route's next itinerary is offered instead.
   */
  @Test
  void aDepartedChoiceGoesToTheAlternative() {
    Leg departed = leg(ALPHA, 30, "MAD", "BCN", "2026-11-20T07:00:00Z", Legs.QUOTED_AT);
    Itinerary choice = trip(departed);
    Itinerary next = toBarcelona(GAMMA, 11, "180.00");
    reader.with(choice, next);
    sells(offer(departed, ALPHA, "100.00"));
    Leg confirmed = offer(next.legs().getFirst(), GAMMA, "180.00");
    sells(confirmed);

    RepriceResult result = service().reprice(choice.id());

    assertThat(result).isEqualTo(new RepriceResult.Alternative(trip(confirmed)));
    assertThat(repository.updated()).containsExactly(trip(confirmed));
    assertThat(asked).doesNotContain(new Quote(ALPHA, departed.key()));
  }

  /**
   * Review Focus 4: with every provider down, nothing is confirmed and nothing stored. The choice,
   * the route's cheapest trip, and its three cheapest alternatives are tried, each leg asked of
   * every provider that stored it, and no other trip: at most {@code 1 + maxAlternatives}.
   */
  @Test
  void answersUnconfirmableWithAllProvidersDown() {
    Itinerary choice = toBarcelona(ALPHA, 40, "100.00");
    List<Itinerary> route =
        List.of(
            choice,
            toBarcelona(BETA, 41, "150.00"),
            toBarcelona(GAMMA, 42, "160.00"),
            toBarcelona(ALPHA, 43, "170.00"),
            toBarcelona(BETA, 44, "180.00"));
    route.forEach(reader::with);
    for (Provider provider : Provider.values()) {
      offers.with(
          provider,
          route.stream().map(trip -> offer(trip.legs().getFirst(), provider, "100.00")).toList());
      down(provider);
    }

    RepriceResult result = service().reprice(choice.id());

    assertThat(result).isEqualTo(new RepriceResult.Unconfirmable());
    assertThat(repository.updated()).isEmpty();
    assertThat(asked)
        .hasSize(4 * Provider.values().length)
        .extracting(Quote::flight)
        .containsOnly(
            route.subList(0, 4).stream()
                .map(trip -> trip.legs().getFirst().key())
                .toArray(LegKey[]::new));
  }

  /** Madrid to Barcelona through London on {@code IB<number>} and the next number, both Alpha's. */
  private static Itinerary viaLondon(int number, String firstLegEuros) {
    return trip(
        pricedAt(
            leg(ALPHA, number, "MAD", "LHR", "2026-11-25T07:15:00Z", Legs.QUOTED_AT),
            firstLegEuros),
        leg(ALPHA, number + 1, "LHR", "BCN", "2026-11-25T11:15:00Z", Legs.QUOTED_AT));
  }

  /**
   * Each quote takes 300 ms and fails, and the budget is 500 ms. The choice's two legs are quoted
   * at once and fail at 300 ms, inside the budget, so the first alternative's two are quoted then;
   * they fail at 600 ms, past it, so the second alternative's are never asked. The clock moves only
   * when a quote ends, so no stall of the machine counts: each quote waits until the other leg of
   * its trip has started, then moves the clock to its own end. Legs quoted in turn would never
   * meet, and the quote that waited in vain fails the re-price.
   */
  @Test
  void startsNoQuoteAfterItsBudget() {
    AtomicReference<Instant> now = new AtomicReference<>(NOW);
    clock = now::get;
    settings = new RepriceSettings(3, Duration.ofMillis(500));
    CyclicBarrier bothLegsStarted = new CyclicBarrier(2);
    eachQuote =
        start -> {
          bothLegsStarted.await(5, TimeUnit.SECONDS);
          now.accumulateAndGet(
              start.plusMillis(300), BinaryOperator.maxBy(Comparator.naturalOrder()));
        };
    List<Itinerary> route =
        List.of(
            viaLondon(50, "100.00"),
            viaLondon(52, "110.00"),
            viaLondon(54, "120.00"),
            viaLondon(56, "130.00"));
    route.forEach(reader::with);
    down(ALPHA);

    RepriceResult result = service().reprice(route.getFirst().id());

    assertThat(result).isEqualTo(new RepriceResult.Unconfirmable());
    assertThat(asked)
        .extracting(Quote::flight)
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(route.get(0).legs().stream(), route.get(1).legs().stream())
                .map(Leg::key)
                .toList());
    Instant first = quoteStarts.stream().min(Comparator.naturalOrder()).orElseThrow();
    assertThat(quoteStarts).allMatch(start -> start.isBefore(first.plus(settings.budget())));
  }

  /**
   * A provider that fails in a way its port does not name, neither unavailable nor over its limit,
   * has a bug: the failure propagates as it was thrown, from the leg's own thread, and nothing is
   * stored.
   */
  @Test
  void propagatesAProviderFailureOfAnotherKind() {
    Itinerary choice = trip(MAD_LHR, LHR_BCN);
    reader.with(choice);
    sells(offer(MAD_LHR, ALPHA, "100.00"));
    IllegalStateException bug = new IllegalStateException("a bug in Beta's adapter");
    failing.put(BETA, bug);

    assertThatThrownBy(() -> service().reprice(choice.id())).isSameAs(bug);
    assertThat(repository.updated()).isEmpty();
  }
}
