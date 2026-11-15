package com.flightaggregator.adapter.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BetaProviderTest {

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
        get(urlEqualTo("/beta/segments?page=1&pageSize=100"))
            .willReturn(aResponse().withBody(page(1, 3, 3170))));
    server.stubFor(
        get(urlEqualTo("/beta/segments?page=2&pageSize=100"))
            .willReturn(aResponse().withBody(page(2, 3, 3171))));
    server.stubFor(
        get(urlEqualTo("/beta/segments?page=3&pageSize=100"))
            .willReturn(aResponse().withBody(page(3, 3, 3172))));

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
  void failsWhenTotalPagesExceedsTheMaximum() {
    server.stubFor(
        get(urlEqualTo("/beta/segments?page=1&pageSize=100"))
            .willReturn(aResponse().withBody(page(1, 11, 3170))));

    assertThatThrownBy(() -> provider().download())
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.BETA);
              assertThat(e.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT);
            });
    server.verify(0, getRequestedFor(urlEqualTo("/beta/segments?page=2&pageSize=100")));
  }

  private PagedProvider provider() {
    ResilienceSettings settings =
        new ResilienceSettings(
            Duration.ofMillis(500),
            Duration.ofSeconds(30),
            3,
            Duration.ofMillis(10),
            100,
            10,
            4096);
    AtomicInteger arrivals = new AtomicInteger();
    Clock clock = () -> FIRST_ARRIVAL.plusSeconds(arrivals.getAndIncrement());
    return new PagedProvider(
        new ProviderEndpoint(Provider.BETA, URI.create(server.baseUrl()), "key"),
        settings,
        CircuitBreakerRegistry.of(ProviderHttp.circuitBreakerConfig()),
        clock);
  }

  private static String page(int number, int totalPages, int flightNumber) {
    return "<SegmentsResponse page=\""
        + number
        + "\" totalPages=\""
        + totalPages
        + "\"><Segments><Segment><Carrier code=\"IB\"/><FlightNumber>"
        + flightNumber
        + "</FlightNumber><Departure airport=\"MAD\">2026-11-20T08:15:00+01:00</Departure>"
        + "<Arrival airport=\"LHR\">2026-11-20T09:30:00+00:00</Arrival>"
        + "<PriceCents>12990</PriceCents></Segment></Segments></SegmentsResponse>";
  }
}
