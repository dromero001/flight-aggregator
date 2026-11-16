package com.flightaggregator.bootstrap.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * One WireMock server that answers for the three providers: Alpha sells IB3170 Madrid-London, on
 * its listing and on its quote; Beta and Gamma sell nothing. A run over it publishes one itinerary.
 */
final class OneFlightProviders {

  /** Three days ahead, so that the leg is still sellable whenever the tests run. */
  private static final LocalDate DAY = LocalDate.now(ZoneOffset.UTC).plusDays(3);

  private OneFlightProviders() {}

  /** A server on a free port, reset before each test by the extension itself. */
  static WireMockExtension server() {
    return WireMockExtension.newInstance()
        .options(WireMockConfiguration.options().dynamicPort())
        .build();
  }

  /** Every provider's base URL is the server's. */
  static void baseUrls(DynamicPropertyRegistry registry, WireMockExtension server) {
    for (Provider provider : Provider.values()) {
      registry.add(
          "providers." + provider.name().toLowerCase(Locale.ROOT) + ".base-url", server::baseUrl);
    }
  }

  /** The three providers' answers, stubbed again before each test, after the server's reset. */
  static void answer(WireMockExtension server) {
    server.stubFor(get(urlPathMatching("/alpha/.*")).willReturn(ok(alphaPage())));
    server.stubFor(
        get(urlPathMatching("/beta/.*"))
            .willReturn(
                ok(
                    "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments></Segments>"
                        + "</SegmentsResponse>")));
    server.stubFor(
        get(urlPathMatching("/gamma/.*"))
            .willReturn(ok("airline,flight_number,from,to,departs_at,arrives_at,price_eur\r\n")));
  }

  /** The trace id of every request the server received, read from its {@code traceparent}. */
  static List<String> traceIds(WireMockExtension server) {
    return server.getAllServeEvents().stream()
        .map(ServeEvent::getRequest)
        .map(request -> request.getHeader("traceparent"))
        .map(header -> header == null ? null : header.split("-")[1])
        .toList();
  }

  /** IB3170 Madrid-London, the one record of a listing and of a quote. */
  private static String alphaPage() {
    return "{\"legs\":[{\"carrier\":\"IB\",\"flightNumber\":3170,\"origin\":\"MAD\","
        + "\"destination\":\"LHR\",\"departureTime\":\""
        + DAY
        + "T08:15:00+01:00\",\"arrivalTime\":\""
        + DAY
        + "T09:30:00+00:00\",\"price\":\"129.90\"}],\"nextCursor\":null}";
  }
}
