package com.flightaggregator.adapter.provider.alpha;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlphaProviderTest {

  private static final Instant FIRST_ARRIVAL = Instant.parse("2026-11-15T10:00:00Z");

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
    AlphaProvider provider = provider(Duration.ofMillis(500));

    long started = System.nanoTime();
    Throwable failure = catchThrowable(provider::download);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(failure)
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.TIMEOUT));
    assertThat(elapsed).isLessThan(Duration.ofMillis(800));
  }

  private AlphaProvider provider() {
    return provider(Duration.ofSeconds(30));
  }

  private AlphaProvider provider(Duration downloadTimeout) {
    ResilienceSettings settings =
        new ResilienceSettings(
            Duration.ofMillis(500), downloadTimeout, 3, Duration.ofMillis(10), 100, 10, 4096);
    AtomicInteger arrivals = new AtomicInteger();
    Clock clock = () -> FIRST_ARRIVAL.plusSeconds(arrivals.getAndIncrement());
    ProviderHttp http =
        new ProviderHttp(
            new ProviderEndpoint(Provider.ALPHA, URI.create(server.baseUrl()), "key"),
            settings,
            CircuitBreaker.ofDefaults("alpha"),
            clock);
    return new AlphaProvider(
        http,
        new PagedDownload(Provider.ALPHA, settings, PagedDownload.timeLimiterFor(settings)),
        settings);
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
