package com.flightaggregator.adapter.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.Retryable;

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
  void encodesTheValuesOfTheUriTemplate() {
    server.stubFor(get(urlPathEqualTo("/flights")).willReturn(aResponse().withBody("ok")));

    http().get("/flights?cursor={cursor}", "a+b&c#d{e}");

    server.verify(
        getRequestedFor(urlPathEqualTo("/flights"))
            .withQueryParam("cursor", equalTo("a+b&c#d{e}")));
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
  void doublesTheBackoffBetweenAttempts() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));

    failureOf(http(productionBreakers(), Duration.ofMillis(100)), "/flights");

    List<Instant> arrivals =
        server.getAllServeEvents().stream()
            .map(event -> event.getRequest().getLoggedDate().toInstant())
            .sorted()
            .toList();
    assertThat(arrivals).hasSize(3);
    // A lower bound only: a loaded machine may wait longer, never less.
    assertThat(Duration.between(arrivals.get(1), arrivals.get(2)))
        .isGreaterThanOrEqualTo(Duration.ofMillis(200));
  }

  @Test
  void failsAfterTheLastAttempt() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));

    assertThat(failureOf("/flights")).isEqualTo(FailureReason.HTTP_ERROR);

    server.verify(3, getRequestedFor(urlEqualTo("/flights")));
  }

  /** Each retry, not the first attempt, reaches the listener, which production counts (§6.10). */
  @Test
  void tellsItsListenerOfEachRetry() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));
    AtomicInteger retries = new AtomicInteger();
    RetryListener listener =
        new RetryListener() {
          @Override
          public void beforeRetry(RetryPolicy retryPolicy, Retryable<?> retryable) {
            retries.incrementAndGet();
          }
        };

    failure(http(Provider.ALPHA, productionBreakers(), listener), "/flights");

    assertThat(retries).hasValue(2);
  }

  @Test
  void keepsTheEarlierAttemptsAsSuppressedExceptions() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));

    ProviderUnavailableException failure = failure(http(), "/flights");

    assertThat(failure.getCause()).isInstanceOf(RetryException.class);
    assertThat(failure.getCause().getSuppressed()).hasSize(2);
  }

  @Test
  void doesNotRetryAWrongKey() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(401)));

    assertThat(failureOf("/flights")).isEqualTo(FailureReason.UNAUTHORIZED);

    server.verify(1, getRequestedFor(urlEqualTo("/flights")));
  }

  @Test
  void treatsForbiddenAsUnauthorizedWithoutRetry() {
    server.stubFor(get(urlEqualTo("/forbidden")).willReturn(aResponse().withStatus(403)));

    assertThat(failureOf("/forbidden")).isEqualTo(FailureReason.UNAUTHORIZED);

    server.verify(1, getRequestedFor(urlEqualTo("/forbidden")));
  }

  @Test
  void treatsAnotherClientErrorAsAnHttpErrorWithoutRetry() {
    server.stubFor(get(urlEqualTo("/missing")).willReturn(aResponse().withStatus(404)));

    assertThat(failureOf("/missing")).isEqualTo(FailureReason.HTTP_ERROR);

    server.verify(1, getRequestedFor(urlEqualTo("/missing")));
  }

  @Test
  void timesOutASlowResponse() {
    server.stubFor(
        get(urlEqualTo("/slow")).willReturn(aResponse().withFixedDelay(3000).withBody("late")));

    assertThat(failureOf(httpWithShortTimeout(), "/slow")).isEqualTo(FailureReason.TIMEOUT);

    server.verify(3, getRequestedFor(urlEqualTo("/slow")));
  }

  @Test
  void timesOutAStalledBody() {
    server.stubFor(
        get(urlEqualTo("/stalled"))
            .willReturn(aResponse().withChunkedDribbleDelay(10, 3000).withBody("x".repeat(100))));

    assertThat(failureOf(httpWithShortTimeout(), "/stalled")).isEqualTo(FailureReason.TIMEOUT);

    server.verify(3, getRequestedFor(urlEqualTo("/stalled")));
  }

  @Test
  void failsFastWithAnOpenBreaker() {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(aResponse().withStatus(500)));
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .build());
    ProviderHttp http = http(breakers);
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

  @Test
  void letsAnUnexpectedFailurePropagate() {
    ProviderHttp http = http();

    assertThatThrownBy(() -> http.get("/flights/{missing}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The plan's window: the last 10 calls, open at 50% failures for 60 s, then 3 trial calls. Each
   * provider's calls go through its own breaker of the registry, named after the provider.
   */
  @Test
  void buildsEachProvidersBreakerWithTheProductionWindow() {
    CircuitBreakerRegistry breakers = productionBreakers();
    http(Provider.BETA, breakers, Duration.ofMillis(10), Duration.ofSeconds(5));

    CircuitBreaker breaker = breakers.getAllCircuitBreakers().stream().findFirst().orElseThrow();
    CircuitBreakerConfig config = breaker.getCircuitBreakerConfig();
    assertThat(breakers.getAllCircuitBreakers()).hasSize(1);
    assertThat(breaker.getName()).isEqualTo("BETA");
    assertThat(config.getSlidingWindowType()).isEqualTo(SlidingWindowType.COUNT_BASED);
    assertThat(config.getSlidingWindowSize()).isEqualTo(10);
    assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
    assertThat(config.getFailureRateThreshold()).isEqualTo(50);
    assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(60_000L);
    assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
  }

  /**
   * Only a failure that a wait may heal counts against the circuit breaker: a server error, a
   * timeout or an I/O error, once per attempt. A refused key, another client error, a redirect or a
   * body over the limit comes back the same until the configuration or the provider changes, so the
   * breaker ignores it: neither a failure nor a success. Columns: failed and ignored calls.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("attemptFailures")
  void countsAgainstTheBreakerOnlyAFailureAWaitMayHeal(
      String failure, ResponseDefinitionBuilder response, int failed, int ignored) {
    server.stubFor(get(urlEqualTo("/flights")).willReturn(response));
    CircuitBreakerRegistry breakers = productionBreakers();
    CircuitBreaker breaker = breakers.circuitBreaker(Provider.ALPHA.name());
    AtomicInteger ignoredCalls = ignoredCallsOf(breaker);

    failure(http(breakers, Duration.ofMillis(10), Duration.ofMillis(500)), "/flights");

    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(failed);
    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    assertThat(ignoredCalls).hasValue(ignored);
  }

  static Stream<Arguments> attemptFailures() {
    return Stream.of(
        arguments("server error", aResponse().withStatus(500), 3, 0),
        arguments("timeout", aResponse().withFixedDelay(3000).withBody("late"), 3, 0),
        arguments("I/O error", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), 3, 0),
        arguments("refused key", aResponse().withStatus(401), 0, 1),
        arguments("forbidden", aResponse().withStatus(403), 0, 1),
        arguments("other client error", aResponse().withStatus(404), 0, 1),
        arguments(
            "redirect", aResponse().withStatus(302).withHeader("Location", "/elsewhere"), 0, 1),
        arguments("body over the limit", aResponse().withBody("x".repeat(1025)), 0, 1));
  }

  /** A failure of no attempt, such as a template without its variable, is no provider's fault. */
  @Test
  void ignoresAnUnexpectedFailureInTheBreaker() {
    CircuitBreakerRegistry breakers = productionBreakers();
    CircuitBreaker breaker = breakers.circuitBreaker(Provider.ALPHA.name());
    AtomicInteger ignoredCalls = ignoredCallsOf(breaker);
    ProviderHttp http = http(breakers);

    assertThatThrownBy(() -> http.get("/flights/{missing}"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    assertThat(ignoredCalls).hasValue(1);
  }

  /** Resilience4j counts no ignored call in its metrics, only in its events. */
  private static AtomicInteger ignoredCallsOf(CircuitBreaker breaker) {
    AtomicInteger ignored = new AtomicInteger();
    breaker.getEventPublisher().onIgnoredError(event -> ignored.incrementAndGet());
    return ignored;
  }

  @Test
  void neverLogsTheApiKey() {
    ProviderEndpoint endpoint =
        new ProviderEndpoint(Provider.ALPHA, URI.create("http://localhost:1"), KEY);

    assertThat(endpoint.toString()).doesNotContain(KEY).contains("apiKey=***");
  }

  /** The breakers as production builds them. */
  private static CircuitBreakerRegistry productionBreakers() {
    return CircuitBreakerRegistry.of(ProviderHttp.circuitBreakerConfig());
  }

  private ProviderHttp http() {
    return http(productionBreakers());
  }

  private ProviderHttp http(CircuitBreakerRegistry breakers) {
    return http(breakers, Duration.ofMillis(10));
  }

  /** A cold JVM can take seconds for a first request, so only the timeout tests use a short one. */
  private ProviderHttp httpWithShortTimeout() {
    return http(productionBreakers(), Duration.ofMillis(10), Duration.ofMillis(500));
  }

  private ProviderHttp http(CircuitBreakerRegistry breakers, Duration firstBackoff) {
    return http(breakers, firstBackoff, Duration.ofSeconds(5));
  }

  private ProviderHttp http(
      CircuitBreakerRegistry breakers, Duration firstBackoff, Duration requestTimeout) {
    return http(Provider.ALPHA, breakers, firstBackoff, requestTimeout);
  }

  private ProviderHttp http(
      Provider provider,
      CircuitBreakerRegistry breakers,
      Duration firstBackoff,
      Duration requestTimeout) {
    return http(provider, breakers, firstBackoff, requestTimeout, new RetryListener() {});
  }

  private ProviderHttp http(
      Provider provider, CircuitBreakerRegistry breakers, RetryListener retries) {
    return http(provider, breakers, Duration.ofMillis(10), Duration.ofSeconds(5), retries);
  }

  private ProviderHttp http(
      Provider provider,
      CircuitBreakerRegistry breakers,
      Duration firstBackoff,
      Duration requestTimeout,
      RetryListener retries) {
    ProviderEndpoint endpoint = new ProviderEndpoint(provider, URI.create(server.baseUrl()), KEY);
    ResilienceSettings settings =
        new ResilienceSettings(
            requestTimeout, Duration.ofSeconds(60), 3, firstBackoff, 1000, 200, 1024);
    return new ProviderHttp(endpoint, settings, breakers, retries, clock);
  }

  private FailureReason failureOf(String pathAndQuery) {
    return failureOf(http(), pathAndQuery);
  }

  private static FailureReason failureOf(ProviderHttp http, String pathAndQuery) {
    return failure(http, pathAndQuery).reason();
  }

  private static ProviderUnavailableException failure(ProviderHttp http, String pathAndQuery) {
    ProviderUnavailableException failure =
        catchThrowableOfType(ProviderUnavailableException.class, () -> http.get(pathAndQuery));
    assertThat(failure).as("the call to " + pathAndQuery + " must fail").isNotNull();
    return failure;
  }
}
