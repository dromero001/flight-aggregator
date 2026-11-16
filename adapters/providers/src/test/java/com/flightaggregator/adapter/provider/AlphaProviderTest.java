package com.flightaggregator.adapter.provider;

import static com.flightaggregator.adapter.provider.Fixtures.FLIGHT;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryListener;

class AlphaProviderTest {

  private static final Instant FIRST_ARRIVAL = Instant.parse("2026-11-15T10:00:00Z");

  private static final String QUOTE =
      "/alpha/legs?limit=2&carrier=IB&flightNumber=3170&origin=MAD&destination=LHR"
          + "&departure=2026-11-20T07%3A15%3A00Z";

  private WireMockServer server;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void downloadsEveryPageInOrder() {
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100"))
            .willReturn(aResponse().withBody(page(3170, "\"c1\""))));
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&cursor=c1"))
            .willReturn(aResponse().withBody(page(3171, "\"c2\""))));
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&cursor=c2"))
            .willReturn(aResponse().withBody(page(3172, "null"))));

    ProviderDownload download = provider().download();

    assertThat(download.legs())
        .extracting(Leg::flightNumber, Leg::quotedAt)
        .containsExactly(
            tuple(3170, FIRST_ARRIVAL),
            tuple(3171, FIRST_ARRIVAL.plusSeconds(1)),
            tuple(3172, FIRST_ARRIVAL.plusSeconds(2)));
    assertThat(download.discarded()).isEmpty();
  }

  @Test
  void downloadsOnlyTheOriginsLegs() {
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&origin=MAD"))
            .willReturn(aResponse().withBody(page(3170, "\"c1\""))));
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&cursor=c1&origin=MAD"))
            .willReturn(aResponse().withBody(page(3171, "null"))));

    ProviderDownload download = provider().downloadFrom(new Airport("MAD"));

    assertThat(download.legs()).extracting(Leg::flightNumber).containsExactly(3170, 3171);
  }

  @Test
  void quotesOneFlight() {
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(page(3170, "null"))));

    Optional<Leg> quote = provider().quote(FLIGHT);

    assertThat(quote)
        .get()
        .extracting(Leg::key, Leg::quotedAt)
        .containsExactly(FLIGHT, FIRST_ARRIVAL);
  }

  /** A flight no longer sold is the listing's own empty page, with status 200 (Task 1). */
  @Test
  void quotesNothingForAFlightNoLongerSold() {
    server.stubFor(
        get(urlEqualTo(QUOTE))
            .willReturn(aResponse().withBody("{\"legs\":[],\"nextCursor\":null}")));

    assertThat(provider().quote(FLIGHT)).isEmpty();
  }

  @Test
  void failsAQuoteThatAnswersAnotherFlight() {
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(page(3171, "null"))));

    assertThatThrownBy(() -> provider().quote(FLIGHT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.ALPHA);
              assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE);
            });
  }

  /**
   * A quote asks for room for two records, so a provider that holds two offers of the flight says
   * so on the page instead of behind a "more" signal; the brief's rule for a quote is at most one
   * record, and more than one fails it with {@code MALFORMED_PAGE} (plan 5 Task 2).
   */
  @Test
  void failsAQuoteWithTwoRecordsOfTheFlight() {
    String one = page(3170, "null");
    String record = one.substring(one.indexOf('[') + 1, one.lastIndexOf(']'));
    String two =
        "{\"legs\":["
            + record
            + ","
            + record.replace("129.90", "139.90")
            + "],\"nextCursor\":null}";
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(two)));

    assertThatThrownBy(() -> provider().quote(FLIGHT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE));
  }

  /** A discarded record is not "not sold": the provider answered, but not with a valid offer. */
  @Test
  void failsAQuoteWithAMalformedRecord() {
    String missingFlightNumber = page(3170, "null").replace("\"flightNumber\":3170,", "");
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(missingFlightNumber)));

    assertThatThrownBy(() -> provider().quote(FLIGHT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE));
  }

  @Test
  void failsTheWholeDownloadWhenALaterPageFails() {
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100"))
            .willReturn(aResponse().withBody(page(3170, "\"c1\""))));
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&cursor=c1")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> provider().download())
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.ALPHA);
              assertThat(e.reason()).isEqualTo(FailureReason.HTTP_ERROR);
            });
    server.verify(3, getRequestedFor(urlEqualTo("/alpha/legs?limit=100&cursor=c1")));
  }

  @Test
  void endsTheDownloadWithinItsBudgetEvenWhenEveryRequestIsWithinItsOwn() {
    String slowPage = page(3170, "\"c1\"");
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100"))
            .willReturn(aResponse().withBody(slowPage).withChunkedDribbleDelay(3, 300)));
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&cursor=c1"))
            .willReturn(
                aResponse().withBody(page(3171, "\"c2\"")).withChunkedDribbleDelay(3, 300)));
    server.stubFor(
        get(urlEqualTo("/alpha/legs?limit=100&cursor=c2"))
            .willReturn(aResponse().withBody(page(3172, "null")).withChunkedDribbleDelay(3, 300)));
    PagedProvider provider = provider(Duration.ofMillis(500), Duration.ofMillis(500));

    long started = System.nanoTime();
    Throwable failure = catchThrowable(provider::download);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(failure)
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.TIMEOUT));
    assertThat(elapsed).isLessThan(Duration.ofMillis(800));
  }

  /**
   * Each request may take up to 5 s, so a cold CI runner never turns a slow first answer into a
   * timeout; only the test of the download's own budget uses short limits.
   */
  private PagedProvider provider() {
    return provider(Duration.ofSeconds(5), Duration.ofSeconds(30));
  }

  private PagedProvider provider(Duration requestTimeout, Duration downloadTimeout) {
    ResilienceSettings settings =
        new ResilienceSettings(
            requestTimeout, downloadTimeout, 3, Duration.ofMillis(10), 100, 10, 4096);
    AtomicInteger arrivals = new AtomicInteger();
    Clock clock = () -> FIRST_ARRIVAL.plusSeconds(arrivals.getAndIncrement());
    return new PagedProvider(
        new ProviderEndpoint(Provider.ALPHA, URI.create(server.baseUrl()), "key"),
        settings,
        CircuitBreakerRegistry.of(ProviderHttp.circuitBreakerConfig()),
        new RetryListener() {},
        clock);
  }

  private static String page(int flightNumber, String nextCursor) {
    return "{\"legs\":[{\"carrier\":\"IB\",\"flightNumber\":"
        + flightNumber
        + ",\"origin\":\"MAD\",\"destination\":\"LHR\","
        + "\"departureTime\":\"2026-11-20T08:15:00+01:00\","
        + "\"arrivalTime\":\"2026-11-20T09:30:00+00:00\",\"price\":\"129.90\"}],"
        + "\"nextCursor\":"
        + nextCursor
        + "}";
  }
}
