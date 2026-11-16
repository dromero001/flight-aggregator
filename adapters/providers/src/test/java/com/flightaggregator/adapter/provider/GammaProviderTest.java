package com.flightaggregator.adapter.provider;

import static com.flightaggregator.adapter.provider.Fixtures.FLIGHT;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

class GammaProviderTest {

  private static final Instant FIRST_ARRIVAL = Instant.parse("2026-11-15T10:00:00Z");

  private static final String QUOTE =
      "/gamma/legs.csv?offset=0&limit=1&carrier=IB&flightNumber=3170&origin=MAD&destination=LHR"
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

  @Test
  void downloadsOnlyTheOriginsLegs() {
    server.stubFor(
        get(urlEqualTo("/gamma/legs.csv?offset=0&limit=1&origin=MAD"))
            .willReturn(aResponse().withBody(csv(3170))));
    server.stubFor(
        get(urlEqualTo("/gamma/legs.csv?offset=1&limit=1&origin=MAD"))
            .willReturn(aResponse().withBody(csv())));

    ProviderDownload download = provider().downloadFrom(new Airport("MAD"));

    assertThat(download.legs()).extracting(Leg::flightNumber).containsExactly(3170);
  }

  @Test
  void quotesOneFlight() {
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(csv(3170))));

    Optional<Leg> quote = provider().quote(FLIGHT);

    assertThat(quote)
        .get()
        .extracting(Leg::key, Leg::quotedAt)
        .containsExactly(FLIGHT, FIRST_ARRIVAL);
  }

  /** A flight no longer sold is the listing's own empty page, the header only (Task 1). */
  @Test
  void quotesNothingForAFlightNoLongerSold() {
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(csv())));

    assertThat(provider().quote(FLIGHT)).isEmpty();
  }

  @Test
  void failsAQuoteThatAnswersAnotherFlight() {
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(csv(3171))));

    assertThatThrownBy(() -> provider().quote(FLIGHT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.GAMMA);
              assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE);
            });
  }

  @Test
  void failsAQuoteWithAMalformedRecord() {
    String missingFlightNumber = csv(3170).replace("IB,3170,", "IB,,");
    server.stubFor(get(urlEqualTo(QUOTE)).willReturn(aResponse().withBody(missingFlightNumber)));

    assertThatThrownBy(() -> provider().quote(FLIGHT))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.MALFORMED_PAGE));
  }

  private PagedProvider provider() {
    ResilienceSettings settings =
        new ResilienceSettings(
            Duration.ofSeconds(5), Duration.ofSeconds(30), 3, Duration.ofMillis(10), 1, 10, 4096);
    AtomicInteger arrivals = new AtomicInteger();
    Clock clock = () -> FIRST_ARRIVAL.plusSeconds(arrivals.getAndIncrement());
    return new PagedProvider(
        new ProviderEndpoint(Provider.GAMMA, URI.create(server.baseUrl()), "key"),
        settings,
        CircuitBreakerRegistry.of(ProviderHttp.circuitBreakerConfig()),
        new RetryListener() {},
        clock);
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
