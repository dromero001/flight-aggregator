package com.flightaggregator.adapter.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderHttpTest {

  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00.123456789Z");
  private static final String KEY = "s3cret-key";

  private final Clock clock = () -> NOW;
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

  private ProviderHttp http() {
    return http(CircuitBreaker.ofDefaults("alpha"));
  }

  private ProviderHttp http(CircuitBreaker breaker) {
    ProviderEndpoint endpoint =
        new ProviderEndpoint(Provider.ALPHA, URI.create(server.baseUrl()), KEY);
    ResilienceSettings settings =
        new ResilienceSettings(
            Duration.ofMillis(500),
            Duration.ofSeconds(60),
            3,
            Duration.ofMillis(10),
            1000,
            200,
            1024);
    return new ProviderHttp(endpoint, settings, breaker, clock);
  }

  @Test
  void sendsTheApiKeyAndReturnsTheBody() {
    server.stubFor(
        get(urlEqualTo("/flights?page=0")).willReturn(aResponse().withBody("{\"ok\":1}")));

    ProviderHttp.FetchedPage page = http().get("/flights?page=0");

    assertThat(page.body()).isEqualTo("{\"ok\":1}");
    assertThat(page.receivedAt()).isEqualTo(Instant.parse("2026-11-15T10:00:00.123456Z"));
    server.verify(
        getRequestedFor(urlEqualTo("/flights?page=0")).withHeader("X-Api-Key", equalTo(KEY)));
  }

  @Test
  void retriesAServerErrorThenSucceeds() {
    server.stubFor(
        get(urlEqualTo("/flights"))
            .inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("recovered"));
    server.stubFor(
        get(urlEqualTo("/flights"))
            .inScenario("flaky")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withBody("ok")));

    assertThat(http().get("/flights").body()).isEqualTo("ok");

    server.verify(2, getRequestedFor(urlEqualTo("/flights")));
  }

  @Test
  void failsAfterTheLastAttempt() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));

    assertThat(failureOf("/flights")).isEqualTo(FailureReason.HTTP_ERROR);

    server.verify(3, getRequestedFor(urlEqualTo("/flights")));
  }

  @Test
  void doesNotRetryAWrongKey() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(401)));

    assertThat(failureOf("/flights")).isEqualTo(FailureReason.UNAUTHORIZED);

    server.verify(1, getRequestedFor(urlEqualTo("/flights")));
  }

  @Test
  void mapsForbiddenToUnauthorizedAndOtherClientErrorsToHttpErrorWithoutRetry() {
    server.stubFor(get(urlEqualTo("/forbidden")).willReturn(aResponse().withStatus(403)));
    server.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(404)));

    assertThat(failureOf("/forbidden")).isEqualTo(FailureReason.UNAUTHORIZED);
    assertThat(failureOf("/missing")).isEqualTo(FailureReason.HTTP_ERROR);

    server.verify(1, getRequestedFor(urlEqualTo("/forbidden")));
    server.verify(1, getRequestedFor(urlEqualTo("/missing")));
  }

  @Test
  void timesOutASlowResponse() {
    server.stubFor(
        get(urlEqualTo("/slow")).willReturn(aResponse().withFixedDelay(3000).withBody("late")));

    assertThat(failureOf("/slow")).isEqualTo(FailureReason.TIMEOUT);
  }

  @Test
  void failsFastWithAnOpenBreaker() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));
    CircuitBreaker breaker =
        CircuitBreaker.of(
            "alpha",
            CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .build());
    ProviderHttp http = http(breaker);
    assertThat(failureOf(http, "/flights")).isEqualTo(FailureReason.HTTP_ERROR);

    assertThat(failureOf(http, "/flights")).isEqualTo(FailureReason.CIRCUIT_OPEN);

    server.verify(3, getRequestedFor(urlEqualTo("/flights")));
  }

  @Test
  void rejectsABodyOverTheLimit() {
    server.stubFor(get(urlEqualTo("/big")).willReturn(aResponse().withBody("x".repeat(1025))));
    server.stubFor(get(urlEqualTo("/exact")).willReturn(aResponse().withBody("x".repeat(1024))));

    assertThat(failureOf("/big")).isEqualTo(FailureReason.MALFORMED_PAGE);
    assertThat(http().get("/exact").body()).hasSize(1024);

    server.verify(1, getRequestedFor(urlEqualTo("/big")));
  }

  @Test
  void retriesAnIoErrorAndReportsItAsAnHttpError() {
    server.stubFor(
        get(urlEqualTo("/reset"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(failureOf("/reset")).isEqualTo(FailureReason.HTTP_ERROR);

    server.verify(3, getRequestedFor(urlEqualTo("/reset")));
  }

  @Test
  void treatsARedirectAsAnHttpErrorWithoutRetry() {
    server.stubFor(
        get(urlEqualTo("/moved"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));

    assertThat(failureOf("/moved")).isEqualTo(FailureReason.HTTP_ERROR);

    server.verify(1, getRequestedFor(urlEqualTo("/moved")));
  }

  private FailureReason failureOf(String pathAndQuery) {
    return failureOf(http(), pathAndQuery);
  }

  private static FailureReason failureOf(ProviderHttp http, String pathAndQuery) {
    return catchThrowableOfType(ProviderUnavailableException.class, () -> http.get(pathAndQuery))
        .reason();
  }

  @Test
  void neverLogsTheApiKey() {
    ProviderEndpoint endpoint =
        new ProviderEndpoint(Provider.ALPHA, URI.create("http://localhost:1"), KEY);

    assertThat(endpoint.toString()).doesNotContain(KEY).contains("apiKey=***");
  }
}
