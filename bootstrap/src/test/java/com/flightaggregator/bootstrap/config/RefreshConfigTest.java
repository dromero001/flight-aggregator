package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.OfferStore;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.application.port.out.StoreTransactions;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The stale-route refresh RefreshConfig declares, driven through the scheduler search asks, over
 * one provider, the stores and the coordination the test holds.
 */
class RefreshConfigTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final Airport MADRID = new Airport("MAD");

  /** Alpha's Madrid-London flight, as it sells it now. */
  private static final Leg MADRID_LONDON =
      new Leg(
          new Carrier("IB"),
          3170,
          MADRID,
          new Airport("LHR"),
          OffsetDateTime.parse("2026-11-25T07:15:00Z"),
          OffsetDateTime.parse("2026-11-25T09:15:00Z"),
          Money.euros("100.00"),
          Provider.ALPHA,
          NOW);

  /** What the refresh's rewrite was given: the origin, and the itineraries its writer pushed. */
  private final CompletableFuture<List<Itinerary>> rewrittenThroughMadrid =
      new CompletableFuture<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(RefreshConfig.class, TracingConfig.class)
          .withBean(FlightProvider.class, Alpha::new)
          .withBean(OfferStore.class, NothingStored::new)
          .withBean(AirportCatalogue.class, () -> () -> Set.of(MADRID, new Airport("LHR")))
          .withBean(DiscardedRecords.class, () -> (provider, record) -> {})
          .withBean(ItineraryRepository.class, () -> new Rewrites())
          .withBean(RefreshCoordination.class, NothingRefreshed::new)
          .withBean(StoreTransactions.class, NoTransaction::new)
          .withBean(
              LockingTaskExecutor.class,
              () -> new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> {})))
          .withBean(Clock.class, () -> () -> NOW)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          // The run's ceiling, which the refresh shares (R7): one, exactly what Madrid builds.
          .withPropertyValues("ingestion.max-itineraries=1");

  /**
   * A refresh asked for is downloaded and rewritten in the background: the scheduler search holds
   * runs the refresh use case over the wired providers and stores.
   */
  @Test
  void refreshesARequestedOriginInTheBackground() {
    runner.run(
        context -> {
          context.getBean(RefreshScheduler.class).request(MADRID);

          assertThat(rewrittenThroughMadrid.get(10, TimeUnit.SECONDS))
              .extracting(Itinerary::legs)
              .containsExactly(List.of(MADRID_LONDON));
        });
  }

  /** Alpha, which sells Madrid-London from Madrid and does nothing else. */
  private static final class Alpha implements FlightProvider {

    @Override
    public Provider provider() {
      return Provider.ALPHA;
    }

    @Override
    public ProviderDownload download() {
      throw new UnsupportedOperationException("A refresh downloads one origin");
    }

    @Override
    public ProviderDownload downloadFrom(Airport origin) {
      return new ProviderDownload(
          origin.equals(MADRID) ? List.of(MADRID_LONDON) : List.of(), List.of());
    }

    @Override
    public Optional<Leg> quote(LegKey flight) {
      throw new UnsupportedOperationException("A refresh quotes nothing");
    }
  }

  /** No offer stored yet; a refresh's fresh offers are not kept. */
  private static final class NothingStored implements OfferStore {

    @Override
    public void replace(Provider provider, List<Leg> offers) {
      throw new UnsupportedOperationException("A refresh replaces no provider's every offer");
    }

    @Override
    public void replaceFrom(Provider provider, Airport origin, List<Leg> offers) {}

    @Override
    public List<Leg> offers(Provider provider) {
      return List.of();
    }

    @Override
    public List<Leg> offersOf(LegKey flight) {
      throw new UnsupportedOperationException("A refresh quotes nothing");
    }
  }

  /** Runs the work as it is given: the test's stores keep nothing to roll back. */
  private static final class NoTransaction implements StoreTransactions {

    @Override
    public <T> T inOne(Supplier<T> work) {
      return work.get();
    }
  }

  /** Completes {@link #rewrittenThroughMadrid} with what a rewrite through Madrid is given. */
  private final class Rewrites implements ItineraryRepository {

    @Override
    public Publication replaceInventory(Instant now, ItineraryWriter writer) {
      throw new UnsupportedOperationException("A refresh loads no inventory");
    }

    @Override
    public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
      List<Itinerary> written = new ArrayList<>();
      writer.writeTo(written::add);
      if (origin.equals(MADRID)) {
        rewrittenThroughMadrid.complete(written);
      }
      return new Publication(2, written.size(), 0, Instant.parse("2026-12-28T00:00:00Z"));
    }

    @Override
    public StoreOutcome updateInPlace(Itinerary repriced) {
      throw new UnsupportedOperationException("A refresh updates nothing in place");
    }
  }

  /** No origin refreshed yet; every claim granted. */
  private static final class NothingRefreshed implements RefreshCoordination {

    @Override
    public Optional<Instant> refreshedAt(Airport origin) {
      return Optional.empty();
    }

    @Override
    public boolean claim(Airport origin) {
      return true;
    }

    @Override
    public void release(Airport origin) {}

    @Override
    public void refreshed(Airport origin, Instant at) {}
  }
}
