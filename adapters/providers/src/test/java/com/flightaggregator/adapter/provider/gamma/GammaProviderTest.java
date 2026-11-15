package com.flightaggregator.adapter.provider.gamma;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.ProviderDownload;
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

class GammaProviderTest {

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
        get(urlEqualTo("/gamma/legs.csv?offset=0&limit=1"))
            .willReturn(aResponse().withBody(csv(3170))));
    server.stubFor(
        get(urlEqualTo("/gamma/legs.csv?offset=1&limit=1"))
            .willReturn(aResponse().withBody(csv(3171))));
    server.stubFor(
        get(urlEqualTo("/gamma/legs.csv?offset=2&limit=1"))
            .willReturn(aResponse().withBody(csv())));

    ProviderDownload download = provider().download();

    assertThat(download.legs())
        .extracting(Leg::flightNumber, Leg::quotedAt)
        .containsExactly(tuple(3170, FIRST_ARRIVAL), tuple(3171, FIRST_ARRIVAL.plusSeconds(1)));
    assertThat(download.discarded()).isEmpty();
  }

  private GammaProvider provider() {
    ResilienceSettings settings =
        new ResilienceSettings(
            Duration.ofMillis(500), Duration.ofSeconds(30), 3, Duration.ofMillis(10), 1, 10, 4096);
    AtomicInteger arrivals = new AtomicInteger();
    Clock clock = () -> FIRST_ARRIVAL.plusSeconds(arrivals.getAndIncrement());
    ProviderHttp http =
        new ProviderHttp(
            new ProviderEndpoint(Provider.GAMMA, URI.create(server.baseUrl()), "key"),
            settings,
            CircuitBreaker.ofDefaults("gamma"),
            clock);
    return new GammaProvider(
        http,
        new PagedDownload(Provider.GAMMA, settings, PagedDownload.timeLimiterFor(settings)),
        settings);
  }

  private static String csv(int... flightNumbers) {
    StringBuilder out =
        new StringBuilder("airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n");
    for (int flightNumber : flightNumbers) {
      out.append("IB,")
          .append(flightNumber)
          .append(",MAD,LHR,2026-11-20T08:15:00+01:00,2026-11-20T09:30:00+00:00,129.90\r\n");
    }
    return out.toString();
  }
}
