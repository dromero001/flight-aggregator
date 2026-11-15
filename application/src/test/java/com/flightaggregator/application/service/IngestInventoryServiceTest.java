package com.flightaggregator.application.service;

import static com.flightaggregator.application.service.Legs.flyingFor;
import static com.flightaggregator.application.service.Legs.leg;
import static com.flightaggregator.application.service.Legs.pricedAt;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.service.fakes.FakeFlightProvider;
import com.flightaggregator.application.service.fakes.FakeItineraryRepository;
import com.flightaggregator.application.service.fakes.InMemoryOfferStore;
import com.flightaggregator.application.service.fakes.RecordingDiscardedRecords;
import com.flightaggregator.application.service.fakes.RecordingDiscardedRecords.Entry;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IngestInventoryServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final long MAX_ITINERARIES = 1_000;
  private static final AirportCatalogue CATALOGUE =
      () -> Set.of(new Airport("MAD"), new Airport("LHR"), new Airport("BCN"));

  private final InMemoryOfferStore store = new InMemoryOfferStore();
  private final RecordingDiscardedRecords discarded = new RecordingDiscardedRecords();
  private final FakeItineraryRepository repository = new FakeItineraryRepository();

  private IngestInventoryService service(FakeFlightProvider... providers) {
    return serviceWithCeiling(MAX_ITINERARIES, providers);
  }

  private IngestInventoryService serviceWithCeiling(
      long maxItineraries, FakeFlightProvider... providers) {
    return new IngestInventoryService(
        List.of(providers), store, CATALOGUE, discarded, repository, () -> NOW, maxItineraries);
  }

  private static ProviderDownload download(Leg... legs) {
    return new ProviderDownload(List.of(legs), List.of());
  }

  @Test
  void storesACompleteDownload() {
    List<Leg> legs =
        List.of(
            leg(ALPHA, 1, "MAD", "LHR"), leg(ALPHA, 2, "MAD", "BCN"), leg(ALPHA, 3, "BCN", "LHR"));

    IngestionReport report =
        service(new FakeFlightProvider(ALPHA, () -> new ProviderDownload(legs, List.of())))
            .ingest();

    assertThat(store.offers(ALPHA)).isEqualTo(legs);
    assertThat(report.providers()).hasSize(1);
    ProviderReport alpha = report.providers().getFirst();
    assertThat(alpha.provider()).isEqualTo(ALPHA);
    assertThat(alpha.status()).isEqualTo(ProviderReport.Status.FRESH);
    assertThat(alpha.offersFetched()).isEqualTo(3);
  }

  @Test
  void keepsAFailedProvidersOffersWhole() {
    List<Leg> lastGood = List.of(leg(BETA, 7, "MAD", "LHR"));
    store.with(BETA, lastGood);
    ProviderUnavailableException timedOut =
        new ProviderUnavailableException(
            BETA, FailureReason.TIMEOUT, new IOException("read timed out"));
    FakeFlightProvider beta =
        new FakeFlightProvider(
            BETA,
            () -> {
              throw timedOut;
            });

    IngestionReport report = service(beta).ingest();

    assertThat(store.replacedProviders()).doesNotContain(BETA);
    assertThat(store.offers(BETA)).isEqualTo(lastGood);
    ProviderReport betaReport = report.providers().getFirst();
    assertThat(betaReport.status()).isEqualTo(ProviderReport.Status.KEPT);
    assertThat(betaReport.failure()).contains(FailureReason.TIMEOUT);
    // The exception itself, which names the provider and the reason above its own cause.
    assertThat(betaReport.cause()).containsSame(timedOut);
    assertThat(betaReport.offersFetched()).isZero();
    assertThat(betaReport.offersUsed()).isEqualTo(1);
  }

  /** The offers a run keeps are read from the store once, for the build and the report alike. */
  @Test
  void readsAKeptProvidersStoredOffersOnce() {
    store.with(BETA, List.of(leg(BETA, 7, "MAD", "LHR")));
    FakeFlightProvider beta =
        new FakeFlightProvider(
            BETA,
            () -> {
              throw new ProviderUnavailableException(BETA, FailureReason.HTTP_ERROR, null);
            });

    service(beta).ingest();

    assertThat(store.reads(BETA)).isEqualTo(1);
  }

  /**
   * How old each provider's data is: the newest quote among its stored offers, just stored or kept,
   * so that a run after a restart knows it too. A provider with nothing stored has none.
   */
  @Test
  void reportsTheNewestQuoteOfEachProvidersStoredOffers() {
    Instant firstPage = Instant.parse("2026-11-20T07:59:58Z");
    Instant secondPage = Instant.parse("2026-11-20T07:59:59Z");
    Instant yesterday = Instant.parse("2026-11-19T08:00:05Z");
    store.with(
        BETA,
        List.of(
            leg(BETA, 5, "MAD", "LHR", "2026-11-25T07:15:00Z", yesterday.minusSeconds(4)),
            leg(BETA, 6, "MAD", "BCN", "2026-11-25T07:15:00Z", yesterday)));
    FakeFlightProvider alpha =
        new FakeFlightProvider(
            ALPHA,
            () ->
                download(
                    leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", secondPage),
                    leg(ALPHA, 2, "MAD", "BCN", "2026-11-25T07:15:00Z", firstPage)));
    FakeFlightProvider beta =
        new FakeFlightProvider(
            BETA,
            () -> {
              throw new ProviderUnavailableException(BETA, FailureReason.HTTP_ERROR, null);
            });
    FakeFlightProvider gamma = new FakeFlightProvider(GAMMA, () -> download());

    IngestionReport report = service(alpha, beta, gamma).ingest();

    assertThat(report.providers())
        .extracting(ProviderReport::provider, ProviderReport::newestQuotedAt)
        .containsExactly(
            tuple(ALPHA, Optional.of(secondPage)),
            tuple(BETA, Optional.of(yesterday)),
            tuple(GAMMA, Optional.empty()));
  }

  @Test
  void treatsAnUnexpectedExceptionAsAFailedProvider() {
    List<Leg> gammaLegs = List.of(leg(GAMMA, 9, "BCN", "LHR"));
    IllegalStateException boom = new IllegalStateException("boom");
    FakeFlightProvider alpha =
        new FakeFlightProvider(
            ALPHA,
            () -> {
              throw boom;
            });
    FakeFlightProvider gamma = new FakeFlightProvider(GAMMA, () -> download(gammaLegs.getFirst()));

    IngestionReport report = service(alpha, gamma).ingest();

    ProviderReport alphaReport = report.providers().getFirst();
    assertThat(alphaReport.status()).isEqualTo(ProviderReport.Status.KEPT);
    assertThat(alphaReport.failure()).contains(FailureReason.UNEXPECTED);
    assertThat(alphaReport.cause()).containsSame(boom);
    assertThat(store.replacedProviders()).containsExactly(GAMMA);
    assertThat(store.offers(GAMMA)).isEqualTo(gammaLegs);
  }

  @Test
  void downloadsTheProvidersInParallel() {
    CountDownLatch allStarted = new CountDownLatch(3);
    Map<Provider, Boolean> met = new ConcurrentHashMap<>();
    FakeFlightProvider[] providers =
        Arrays.stream(Provider.values())
            .map(
                provider ->
                    new FakeFlightProvider(
                        provider,
                        () -> {
                          allStarted.countDown();
                          met.put(provider, awaitAll(allStarted));
                          return download();
                        }))
            .toArray(FakeFlightProvider[]::new);

    service(providers).ingest();

    assertThat(met).containsOnly(entry(ALPHA, true), entry(BETA, true), entry(GAMMA, true));
  }

  @Test
  void reportsTheProvidersInProviderOrder() {
    IngestionReport report =
        service(
                new FakeFlightProvider(GAMMA, IngestInventoryServiceTest::download),
                new FakeFlightProvider(ALPHA, IngestInventoryServiceTest::download),
                new FakeFlightProvider(BETA, IngestInventoryServiceTest::download))
            .ingest();

    assertThat(report.providers())
        .extracting(ProviderReport::provider)
        .containsExactly(ALPHA, BETA, GAMMA);
  }

  @Test
  void discardsLegsWithAnAirportOutsideTheCatalogue() {
    Leg known = leg(ALPHA, 1, "MAD", "LHR");
    Leg unknown = leg(ALPHA, 2, "MAD", "ZZZ");
    FakeFlightProvider alpha = new FakeFlightProvider(ALPHA, () -> download(known, unknown));

    IngestionReport report = service(alpha).ingest();

    assertThat(store.offers(ALPHA)).containsExactly(known);
    assertThat(discarded.entries())
        .extracting(Entry::provider, entry -> entry.record().reason())
        .containsExactly(tuple(ALPHA, DiscardReason.UNKNOWN_AIRPORT));
    ProviderReport alphaReport = report.providers().getFirst();
    assertThat(alphaReport.offersFetched()).isEqualTo(2);
    assertThat(alphaReport.offersUsed()).isEqualTo(1);
    assertThat(alphaReport.discarded()).isEqualTo(Map.of(DiscardReason.UNKNOWN_AIRPORT, 1));
  }

  @Test
  void reportsEveryDiscardedRecordOfTheDownload() {
    DiscardedRecord first = new DiscardedRecord(DiscardReason.SHAPE, "{\"id\":1}");
    DiscardedRecord second = new DiscardedRecord(DiscardReason.SHAPE, "{\"id\":2}");
    FakeFlightProvider beta =
        new FakeFlightProvider(BETA, () -> new ProviderDownload(List.of(), List.of(first, second)));

    IngestionReport report = service(beta).ingest();

    assertThat(discarded.entries())
        .containsExactly(new Entry(BETA, first), new Entry(BETA, second));
    assertThat(report.providers().getFirst().discarded()).isEqualTo(Map.of(DiscardReason.SHAPE, 2));
  }

  @Test
  void publishesTheItinerariesOfAllStoredOffers() {
    Instant betaQuotedAt = Instant.parse("2026-11-19T08:00:00Z");
    Leg madLhr = leg(ALPHA, 1, "MAD", "LHR", "2026-11-25T07:15:00Z", NOW);
    Leg lhrJfk = leg(BETA, 2, "LHR", "JFK", "2026-11-25T10:30:00Z", betaQuotedAt);
    store.with(BETA, List.of(lhrJfk));
    FakeFlightProvider alpha = new FakeFlightProvider(ALPHA, () -> download(madLhr));
    FakeFlightProvider beta =
        new FakeFlightProvider(
            BETA,
            () -> {
              throw new ProviderUnavailableException(BETA, FailureReason.TIMEOUT, null);
            });

    IngestionReport report = service(alpha, beta).ingest();

    assertThat(repository.published())
        .extracting(Itinerary::legs)
        .containsExactlyInAnyOrder(List.of(madLhr), List.of(lhrJfk), List.of(madLhr, lhrJfk));
    assertThat(repository.published())
        .filteredOn(itinerary -> itinerary.legs().contains(lhrJfk))
        .extracting(Itinerary::quotedAt)
        .containsOnly(betaQuotedAt);
    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.PUBLISHED);
    assertThat(report.publication())
        .contains(
            new Publication(
                FakeItineraryRepository.GENERATION,
                3,
                FakeItineraryRepository.ROWS_IN_DEFAULT_PARTITION,
                FakeItineraryRepository.PARTITIONED_UNTIL));
  }

  @Test
  void buildsWithTheRunsStartInstant() {
    Leg departed = leg(ALPHA, 1, "MAD", "LHR", "2026-11-19T10:00:00Z", NOW);
    FakeFlightProvider alpha = new FakeFlightProvider(ALPHA, () -> download(departed));

    service(alpha).ingest();

    assertThat(repository.published()).isEmpty();
  }

  @Test
  void reportsConflictingOffersAsDiscarded() {
    Leg twoHours = leg(GAMMA, 1, "MAD", "LHR");
    Leg threeHours = flyingFor(twoHours, 3);
    FakeFlightProvider gamma = new FakeFlightProvider(GAMMA, () -> download(twoHours, threeHours));

    IngestionReport report = service(gamma).ingest();

    String flight = twoHours.key().toString();
    assertThat(discarded.entries())
        .containsExactly(
            new Entry(GAMMA, new DiscardedRecord(DiscardReason.CONFLICTING, flight)),
            new Entry(GAMMA, new DiscardedRecord(DiscardReason.CONFLICTING, flight)));
    assertThat(repository.published()).isEmpty();
    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.NOTHING_TO_PUBLISH);
    ProviderReport gammaReport = report.providers().getFirst();
    assertThat(gammaReport.discarded()).isEqualTo(Map.of(DiscardReason.CONFLICTING, 2));
    assertThat(gammaReport.offersUsed()).isZero();
  }

  @Test
  void countsAnOfferBeatenByACheaperOneOfAnotherProvider() {
    Leg dearer = leg(ALPHA, 1, "MAD", "LHR");
    Leg cheaper = pricedAt(leg(BETA, 1, "MAD", "LHR"), "80.00");
    FakeFlightProvider alpha = new FakeFlightProvider(ALPHA, () -> download(dearer));
    FakeFlightProvider beta = new FakeFlightProvider(BETA, () -> download(cheaper));

    IngestionReport report = service(alpha, beta).ingest();

    assertThat(report.providers())
        .extracting(ProviderReport::provider, ProviderReport::lostToDeduplication)
        .containsExactly(tuple(ALPHA, 1), tuple(BETA, 0));
    assertThat(repository.published())
        .extracting(Itinerary::legs)
        .containsExactly(List.of(cheaper));
  }

  @Test
  void publishesNothingWithoutOffers() {
    FakeFlightProvider alpha =
        new FakeFlightProvider(
            ALPHA,
            () -> {
              throw new ProviderUnavailableException(ALPHA, FailureReason.HTTP_ERROR, null);
            });

    IngestionReport report = service(alpha).ingest();

    assertThat(repository.calls()).isZero();
    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.NOTHING_TO_PUBLISH);
    assertThat(report.publication()).isEmpty();
  }

  @Test
  void stopsWithoutPublishingPastTheCeiling() {
    FakeFlightProvider alpha =
        new FakeFlightProvider(
            ALPHA,
            () ->
                download(
                    leg(ALPHA, 1, "MAD", "LHR"),
                    leg(ALPHA, 2, "MAD", "BCN"),
                    leg(ALPHA, 3, "BCN", "LHR", "2026-11-25T10:30:00Z", NOW)));

    IngestionReport report = serviceWithCeiling(2, alpha).ingest();

    assertThat(repository.calls()).isEqualTo(1);
    assertThat(repository.published()).isEmpty();
    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.CEILING_EXCEEDED);
    assertThat(report.publication()).isEmpty();
  }

  @Test
  void publishesNothingWhenNoItineraryCanBeBuilt() {
    Leg departed = leg(ALPHA, 1, "MAD", "LHR", "2026-11-19T10:00:00Z", NOW);
    FakeFlightProvider alpha = new FakeFlightProvider(ALPHA, () -> download(departed));

    IngestionReport report = service(alpha).ingest();

    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.NOTHING_TO_PUBLISH);
    assertThat(report.publication()).isEmpty();
    assertThat(repository.published()).isEmpty();
  }

  @Test
  void publishesExactlyTheCeiling() {
    FakeFlightProvider alpha =
        new FakeFlightProvider(
            ALPHA,
            () ->
                download(
                    leg(ALPHA, 1, "MAD", "LHR"),
                    leg(ALPHA, 2, "MAD", "BCN"),
                    leg(ALPHA, 3, "BCN", "LHR", "2026-11-25T10:30:00Z", NOW)));

    IngestionReport report = serviceWithCeiling(4, alpha).ingest();

    assertThat(report.outcome()).isEqualTo(IngestionReport.Outcome.PUBLISHED);
    assertThat(repository.published()).hasSize(4);
  }

  @Test
  void propagatesARepositoryFailure() {
    IllegalStateException failure = new IllegalStateException("database down");
    ItineraryRepository failing =
        (now, writer) -> {
          throw failure;
        };
    Leg madLhr = leg(ALPHA, 1, "MAD", "LHR");
    FakeFlightProvider alpha = new FakeFlightProvider(ALPHA, () -> download(madLhr));
    IngestInventoryService service =
        new IngestInventoryService(
            List.of(alpha), store, CATALOGUE, discarded, failing, () -> NOW, MAX_ITINERARIES);

    assertThatThrownBy(service::ingest).isSameAs(failure);
    assertThat(store.offers(ALPHA)).containsExactly(madLhr);
  }

  @Test
  void measuresTheRunWithTheClockPort() {
    Iterator<Instant> instants = List.of(NOW, NOW.plusSeconds(5)).iterator();
    FakeFlightProvider alpha =
        new FakeFlightProvider(ALPHA, () -> download(leg(ALPHA, 1, "MAD", "LHR")));
    IngestInventoryService service =
        new IngestInventoryService(
            List.of(alpha),
            store,
            CATALOGUE,
            discarded,
            repository,
            instants::next,
            MAX_ITINERARIES);

    IngestionReport report = service.ingest();

    assertThat(report.startedAt()).isEqualTo(NOW);
    assertThat(report.duration()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void rejectsTwoProvidersWithTheSameName() {
    FakeFlightProvider first = new FakeFlightProvider(BETA, IngestInventoryServiceTest::download);
    FakeFlightProvider second = new FakeFlightProvider(BETA, IngestInventoryServiceTest::download);

    assertThatThrownBy(() -> service(first, second))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BETA");
  }

  private static boolean awaitAll(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
