package com.flightaggregator.application.service;

import static com.flightaggregator.application.service.Legs.leg;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.application.port.in.ProviderReport;
import com.flightaggregator.application.port.out.AirportCatalogue;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.application.service.fakes.FakeFlightProvider;
import com.flightaggregator.application.service.fakes.InMemoryOfferStore;
import com.flightaggregator.application.service.fakes.RecordingDiscardedRecords;
import com.flightaggregator.application.service.fakes.RecordingDiscardedRecords.Entry;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IngestInventoryServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final AirportCatalogue CATALOGUE =
      () -> Set.of(new Airport("MAD"), new Airport("LHR"), new Airport("BCN"));

  private final InMemoryOfferStore store = new InMemoryOfferStore();
  private final RecordingDiscardedRecords discarded = new RecordingDiscardedRecords();

  private IngestInventoryService service(FakeFlightProvider... providers) {
    return new IngestInventoryService(List.of(providers), store, CATALOGUE, discarded, () -> NOW);
  }

  private static ProviderDownload download(Leg... legs) {
    return new ProviderDownload(List.of(legs), List.of());
  }

  @Test
  void storesACompleteDownload() {
    List<Leg> legs =
        List.of(
            leg(Provider.ALPHA, 1, "MAD", "LHR"),
            leg(Provider.ALPHA, 2, "MAD", "BCN"),
            leg(Provider.ALPHA, 3, "BCN", "LHR"));

    IngestionReport report =
        service(new FakeFlightProvider(Provider.ALPHA, () -> new ProviderDownload(legs, List.of())))
            .ingest();

    assertThat(store.offers(Provider.ALPHA)).isEqualTo(legs);
    assertThat(report.providers()).hasSize(1);
    ProviderReport alpha = report.providers().getFirst();
    assertThat(alpha.provider()).isEqualTo(Provider.ALPHA);
    assertThat(alpha.status()).isEqualTo(ProviderReport.Status.FRESH);
    assertThat(alpha.offersFetched()).isEqualTo(3);
  }

  @Test
  void keepsAFailedProvidersOffersWhole() {
    List<Leg> lastGood = List.of(leg(Provider.BETA, 7, "MAD", "LHR"));
    store.with(Provider.BETA, lastGood);
    FakeFlightProvider beta =
        new FakeFlightProvider(
            Provider.BETA,
            () -> {
              throw new ProviderUnavailableException(
                  Provider.BETA, FailureReason.TIMEOUT, new IOException("read timed out"));
            });

    IngestionReport report = service(beta).ingest();

    assertThat(store.replacedProviders()).doesNotContain(Provider.BETA);
    assertThat(store.offers(Provider.BETA)).isEqualTo(lastGood);
    ProviderReport betaReport = report.providers().getFirst();
    assertThat(betaReport.status()).isEqualTo(ProviderReport.Status.KEPT);
    assertThat(betaReport.failure()).contains(FailureReason.TIMEOUT);
    assertThat(betaReport.offersFetched()).isZero();
    assertThat(betaReport.offersUsed()).isEqualTo(1);
  }

  @Test
  void treatsAnUnexpectedExceptionAsAFailedProvider() {
    List<Leg> gammaLegs = List.of(leg(Provider.GAMMA, 9, "BCN", "LHR"));
    FakeFlightProvider alpha =
        new FakeFlightProvider(
            Provider.ALPHA,
            () -> {
              throw new IllegalStateException("boom");
            });
    FakeFlightProvider gamma =
        new FakeFlightProvider(Provider.GAMMA, () -> download(gammaLegs.getFirst()));

    IngestionReport report = service(alpha, gamma).ingest();

    ProviderReport alphaReport = report.providers().getFirst();
    assertThat(alphaReport.status()).isEqualTo(ProviderReport.Status.KEPT);
    assertThat(alphaReport.failure()).contains(FailureReason.UNEXPECTED);
    assertThat(store.replacedProviders()).containsExactly(Provider.GAMMA);
    assertThat(store.offers(Provider.GAMMA)).isEqualTo(gammaLegs);
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

  private static boolean awaitAll(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
