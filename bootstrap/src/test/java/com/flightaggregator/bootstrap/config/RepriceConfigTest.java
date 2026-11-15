package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.RepriceItinerary;
import com.flightaggregator.application.port.in.RepriceResult;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SeekPosition;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.InventoryState;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.application.port.out.StoredPage;
import com.flightaggregator.application.service.RepriceItineraryService;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.ItineraryId;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The re-price use case RepriceConfig declares, driven over a route, a provider and a clock the
 * test holds, so that Q9's values are read from what the wired use case does, as the partition
 * horizon is read from what a run creates.
 */
class RepriceConfigTest {

  private static final Instant START = Instant.parse("2026-11-20T08:00:00Z");

  /** The route's trips, the cheapest first; the first is the choice. */
  private List<Itinerary> route = List.of();

  /** Every flight Alpha was asked to quote, in order. */
  private final Queue<LegKey> asked = new ConcurrentLinkedQueue<>();

  /** The use case's clock: the start, until a quote moves it. */
  private final AtomicReference<Instant> now = new AtomicReference<>(START);

  /** How long after the start each of Alpha's quotes ends, failing: it moves the clock there. */
  private Duration eachQuoteEndsAfter = Duration.ZERO;

  /** Whether Alpha sells the route's flights now, as stored; otherwise it is down. */
  private boolean alphaSells;

  /** What each update in place answers. */
  private StoreOutcome storeAnswers = StoreOutcome.STORED;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(RepriceConfig.class)
          .withBean(ItineraryReader.class, () -> new Route())
          .withBean(OfferStore.class, NoOtherOffers::new)
          .withBean(ItineraryRepository.class, () -> new Stores())
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(Clock.class, () -> now::get)
          .withBean(FlightProvider.class, () -> new Alpha());

  @Test
  void declaresTheRepriceUseCase() {
    runner.run(
        context ->
            assertThat(context)
                .getBean(RepriceItinerary.class)
                .isInstanceOf(RepriceItineraryService.class));
  }

  /**
   * Q9: with Alpha down, the choice and the route's three cheapest other trips are tried, in turn,
   * and no fourth one.
   */
  @Test
  void triesTheChoiceAndThreeAlternatives() {
    route = IntStream.rangeClosed(1, 6).mapToObj(RepriceConfigTest::toBarcelona).toList();

    runner.run(
        context -> {
          RepriceResult result =
              context.getBean(RepriceItinerary.class).reprice(route.getFirst().id());

          assertThat(result).isEqualTo(new RepriceResult.Unconfirmable());
          assertThat(asked)
              .containsExactlyElementsOf(
                  route.subList(0, 4).stream().map(trip -> trip.legs().getFirst().key()).toList());
        });
  }

  /**
   * Q9: no quote starts once 10 s have passed since the re-price started. The choice's quote fails
   * just before then, or just then, and the route's one other trip is quoted after it, or not.
   */
  @ParameterizedTest
  @CsvSource({"PT9.999S, 2", "PT10S, 1"})
  void startsNoQuoteTenSecondsAfterTheStart(Duration choiceQuoteEndsAfter, int tripsQuoted) {
    route = List.of(toBarcelona(1), toBarcelona(2));
    eachQuoteEndsAfter = choiceQuoteEndsAfter;

    runner.run(
        context -> {
          context.getBean(RepriceItinerary.class).reprice(route.getFirst().id());

          assertThat(asked)
              .containsExactlyElementsOf(
                  route.subList(0, tripsQuoted).stream()
                      .map(trip -> trip.legs().getFirst().key())
                      .toList());
        });
  }

  /**
   * Each update in place of the wired use case is counted under what became of it (§6.10); a price
   * confirmed while a run holds the inventory is still the answer.
   */
  @Test
  void countsWhatBecameOfEachStore() {
    route = List.of(toBarcelona(1));
    alphaSells = true;
    storeAnswers = StoreOutcome.BUSY;

    runner.run(
        context -> {
          RepriceResult result =
              context.getBean(RepriceItinerary.class).reprice(route.getFirst().id());

          assertThat(result).isEqualTo(new RepriceResult.Confirmed(route.getFirst()));
          assertThat(
                  context
                      .getBean(SimpleMeterRegistry.class)
                      .get("reprice.store")
                      .tag("outcome", "BUSY")
                      .counter()
                      .count())
              .isEqualTo(1);
        });
  }

  /** {@code IB<number>} from Madrid to Barcelona, sold by Alpha; a higher number costs more. */
  private static Itinerary toBarcelona(int number) {
    OffsetDateTime departure = OffsetDateTime.parse("2026-11-25T09:15:00Z");
    return new Itinerary(
        List.of(
            new Leg(
                new Carrier("IB"),
                number,
                new Airport("MAD"),
                new Airport("BCN"),
                departure,
                departure.plusHours(2),
                Money.euros(100 + number + ".00"),
                Provider.ALPHA,
                START)));
  }

  /** The route's trips, as many as asked, the cheapest first, whatever the criteria. */
  private final class Route implements ItineraryReader {

    @Override
    public InventoryState state() {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoredPage page(
        SearchCriteria criteria, Optional<SeekPosition> after, int limit, Instant at) {
      return new StoredPage(1, route.stream().limit(limit).toList());
    }

    @Override
    public Optional<Itinerary> byId(ItineraryId id) {
      return route.stream().filter(trip -> trip.id().equals(id)).findFirst();
    }
  }

  /** Only a leg's own provider stored an offer of its flight. */
  private static final class NoOtherOffers implements OfferStore {

    @Override
    public void replace(Provider provider, List<Leg> offers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Leg> offers(Provider provider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Leg> offersOf(LegKey flight) {
      return List.of();
    }
  }

  /**
   * A repository whose updates in place answer {@code storeAnswers}; it loads and rewrites nothing.
   */
  private final class Stores implements ItineraryRepository {

    @Override
    public Publication replaceInventory(Instant at, ItineraryWriter writer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoreOutcome updateInPlace(Itinerary repriced) {
      return storeAnswers;
    }
  }

  /**
   * Alpha: each quote is recorded, and sells the route's flight as stored, or moves the clock and
   * times out.
   */
  private final class Alpha implements FlightProvider {

    @Override
    public Provider provider() {
      return Provider.ALPHA;
    }

    @Override
    public ProviderDownload download() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProviderDownload downloadFrom(Airport origin) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Leg> quote(LegKey flight) {
      asked.add(flight);
      if (alphaSells) {
        return route.stream()
            .map(trip -> trip.legs().getFirst())
            .filter(leg -> leg.key().equals(flight))
            .findFirst();
      }
      now.set(START.plus(eachQuoteEndsAfter));
      throw new ProviderUnavailableException(Provider.ALPHA, FailureReason.TIMEOUT, null);
    }
  }
}
