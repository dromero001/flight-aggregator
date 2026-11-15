package com.flightaggregator.application.service;

import static com.flightaggregator.application.service.Legs.QUOTED_AT;
import static com.flightaggregator.application.service.Legs.flyingFor;
import static com.flightaggregator.application.service.Legs.leg;
import static com.flightaggregator.application.service.Legs.pricedAt;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.in.RefreshReport;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.application.service.fakes.FakeFlightProvider;
import com.flightaggregator.application.service.fakes.FakeItineraryRepository;
import com.flightaggregator.application.service.fakes.InMemoryOfferStore;
import com.flightaggregator.application.service.fakes.InMemoryRefreshCoordination;
import com.flightaggregator.application.service.fakes.RecordingDiscardedRecords;
import com.flightaggregator.application.service.fakes.RecordingDiscardedRecords.Entry;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RefreshStaleRouteServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");
  private static final Airport MADRID = new Airport("MAD");
  private static final AirportCatalogue CATALOGUE =
      () ->
          Set.of(
              MADRID,
              new Airport("LHR"),
              new Airport("BCN"),
              new Airport("JFK"),
              new Airport("CDG"));

  private final InMemoryOfferStore store = new InMemoryOfferStore();
  private final RecordingDiscardedRecords discarded = new RecordingDiscardedRecords();
  private final FakeItineraryRepository repository = new FakeItineraryRepository();
  private final InMemoryRefreshCoordination coordination = new InMemoryRefreshCoordination();

  private RefreshStaleRouteService service(FakeFlightProvider... providers) {
    return service(repository, providers);
  }

  private RefreshStaleRouteService service(
      ItineraryRepository itineraries, FakeFlightProvider... providers) {
    return new RefreshStaleRouteService(
        List.of(providers), store, CATALOGUE, discarded, itineraries, coordination, () -> NOW);
  }

  /** A provider whose download of Madrid answers these legs, and of any other origin fails. */
  private static FakeFlightProvider fromMadrid(Provider provider, Leg... legs) {
    return FakeFlightProvider.downloadingFrom(
        provider,
        origin -> {
          assertThat(origin).isEqualTo(MADRID);
          return new ProviderDownload(List.of(legs), List.of());
        });
  }

  /**
   * Alpha sells Madrid-London again, dearer: every itinerary with a leg departing from Madrid is
   * rebuilt with it and pushed, the two-stop trip through Madrid included; the trips that only land
   * in Madrid, or never touch it, are not, and the stored Madrid-London offer takes no part.
   */
  @Test
  void replacesTheItinerariesThroughTheOrigin() {
    Leg madLhr = leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", QUOTED_AT);
    Leg bcnMad = leg(ALPHA, 2, "BCN", "MAD", "2026-11-25T04:00:00Z", QUOTED_AT);
    Leg lhrJfk = leg(ALPHA, 3, "LHR", "JFK", "2026-11-25T10:30:00Z", QUOTED_AT);
    store.with(ALPHA, List.of(madLhr, bcnMad, lhrJfk));
    Leg madLhrNow = pricedAt(leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", NOW), "120.00");

    RefreshReport report = service(fromMadrid(ALPHA, madLhrNow)).refresh(MADRID);

    assertThat(repository.rewrittenThrough()).contains(MADRID);
    assertThat(repository.rewritten())
        .extracting(Itinerary::legs)
        .containsExactlyInAnyOrder(
            List.of(madLhrNow),
            List.of(madLhrNow, lhrJfk),
            List.of(bcnMad, madLhrNow),
            List.of(bcnMad, madLhrNow, lhrJfk));
    assertThat(report)
        .isEqualTo(
            new RefreshReport(
                MADRID,
                RefreshReport.Outcome.REFRESHED,
                FakeItineraryRepository.GENERATION,
                4,
                Map.of(ALPHA, ProviderReport.Status.FRESH)));
  }

  /**
   * Beta's download of Madrid does not arrive, is refused by its concurrency limit, or leaves no
   * usable leg: Beta's stored Madrid offers still build their itineraries, and Beta is kept.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("downloadsThatAreNotFresh")
  void keepsAFailedProvidersStoredOffersOfTheOrigin(
      String download, Function<Airport, ProviderDownload> beta) {
    Leg madCdg = leg(BETA, 7, "MAD", "CDG", "2026-11-25T08:00:00Z", QUOTED_AT);
    store.with(BETA, List.of(madCdg));
    Leg madLhrNow = leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", NOW);

    RefreshReport report =
        service(fromMadrid(ALPHA, madLhrNow), FakeFlightProvider.downloadingFrom(BETA, beta))
            .refresh(MADRID);

    assertThat(repository.rewritten())
        .extracting(Itinerary::legs)
        .containsExactlyInAnyOrder(List.of(madLhrNow), List.of(madCdg));
    assertThat(report.providers())
        .isEqualTo(Map.of(ALPHA, ProviderReport.Status.FRESH, BETA, ProviderReport.Status.KEPT));
  }

  /**
   * With no fresh download, a rewrite would only repeat the stored offers: nothing is written, and
   * the origin is not marked refreshed, so a later search asks again.
   */
  @Test
  void writesNothingWhenEveryProviderFails() {
    store.with(ALPHA, List.of(leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", QUOTED_AT)));
    Function<Airport, ProviderDownload> down =
        origin -> {
          throw new ProviderUnavailableException(ALPHA, FailureReason.HTTP_ERROR, null);
        };

    RefreshReport report =
        service(
                FakeFlightProvider.downloadingFrom(ALPHA, down),
                FakeFlightProvider.downloadingFrom(BETA, down))
            .refresh(MADRID);

    assertThat(report)
        .isEqualTo(
            new RefreshReport(
                MADRID,
                RefreshReport.Outcome.NOTHING_FRESH,
                0,
                0,
                Map.of(ALPHA, ProviderReport.Status.KEPT, BETA, ProviderReport.Status.KEPT)));
    assertThat(repository.rewrittenThrough()).isEmpty();
    assertThat(coordination.refreshedAt(MADRID)).isEmpty();
  }

  /**
   * The origin is marked refreshed, at the refresh's start, once its itineraries are written: a
   * search that reads the mark finds the rewritten rows.
   */
  @Test
  void marksTheOriginRefreshedOnlyAfterTheWrite() {
    AtomicReference<Optional<Instant>> markDuringTheWrite = new AtomicReference<>();
    ItineraryRepository watched =
        new ItineraryRepository() {
          @Override
          public Publication replaceInventory(Instant now, ItineraryWriter writer) {
            throw new UnsupportedOperationException("A refresh loads no inventory");
          }

          @Override
          public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
            markDuringTheWrite.set(coordination.refreshedAt(origin));
            return repository.replaceThrough(origin, writer);
          }

          @Override
          public StoreOutcome updateInPlace(Itinerary repriced) {
            throw new UnsupportedOperationException("A refresh updates nothing in place");
          }
        };
    Leg madLhrNow = leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", NOW);

    service(watched, fromMadrid(ALPHA, madLhrNow)).refresh(MADRID);

    assertThat(markDuringTheWrite).hasValue(Optional.empty());
    assertThat(coordination.refreshedAt(MADRID)).contains(NOW);
  }

  /**
   * A write that fails leaves the origin unmarked, so a search asks again once the claim expires,
   * and the failure reaches the caller as it was thrown, for the caller to report.
   */
  @Test
  void propagatesAFailedWriteAndLeavesTheOriginUnmarked() {
    IllegalStateException failure = new IllegalStateException("lock timeout");
    ItineraryRepository failing =
        new ItineraryRepository() {
          @Override
          public Publication replaceInventory(Instant now, ItineraryWriter writer) {
            throw new UnsupportedOperationException("A refresh loads no inventory");
          }

          @Override
          public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
            throw failure;
          }

          @Override
          public StoreOutcome updateInPlace(Itinerary repriced) {
            throw new UnsupportedOperationException("A refresh updates nothing in place");
          }
        };
    RefreshStaleRouteService service =
        service(
            failing, fromMadrid(ALPHA, leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", NOW)));

    assertThatThrownBy(() -> service.refresh(MADRID)).isSameAs(failure);

    assertThat(coordination.refreshedAt(MADRID)).isEmpty();
  }

  /**
   * As in a run, a provider that gives one flight two arrivals is reported by the download that
   * brought them: Gamma's Madrid offers just downloaded are, while its stored offers from
   * Barcelona, and a kept provider's stored ones from Madrid, were reported by the run that
   * downloaded them.
   */
  @Test
  void reportsTheConflictsOfItsFreshDownloadOnly() {
    Leg madLhr = leg(GAMMA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", NOW);
    Leg bcnLhr = leg(GAMMA, 5, "BCN", "LHR", "2026-11-25T07:15:00Z", QUOTED_AT);
    store.with(GAMMA, List.of(bcnLhr, flyingFor(bcnLhr, 3)));
    Leg madCdg = leg(BETA, 9, "MAD", "CDG", "2026-11-25T08:00:00Z", QUOTED_AT);
    store.with(BETA, List.of(madCdg, flyingFor(madCdg, 3)));
    FakeFlightProvider beta =
        FakeFlightProvider.downloadingFrom(
            BETA,
            origin -> {
              throw new ProviderUnavailableException(BETA, FailureReason.TIMEOUT, null);
            });

    service(fromMadrid(GAMMA, madLhr, flyingFor(madLhr, 3)), beta).refresh(MADRID);

    DiscardedRecord conflicting =
        new DiscardedRecord(DiscardReason.CONFLICTING, madLhr.key().toString());
    assertThat(discarded.entries())
        .containsExactly(new Entry(GAMMA, conflicting), new Entry(GAMMA, conflicting));
  }

  static Stream<Arguments> downloadsThatAreNotFresh() {
    return Stream.of(
        arguments(
            "unavailable",
            (Function<Airport, ProviderDownload>)
                origin -> {
                  throw new ProviderUnavailableException(BETA, FailureReason.TIMEOUT, null);
                }),
        arguments(
            "rejected",
            (Function<Airport, ProviderDownload>)
                origin -> {
                  throw new RejectedExecutionException("over Beta's limit");
                }),
        arguments(
            "no usable leg",
            (Function<Airport, ProviderDownload>)
                origin ->
                    new ProviderDownload(
                        List.of(leg(BETA, 8, "MAD", "ZZZ", "2026-11-25T08:00:00Z", NOW)),
                        List.of())));
  }
}
