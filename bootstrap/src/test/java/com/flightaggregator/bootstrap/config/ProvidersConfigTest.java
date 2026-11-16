package com.flightaggregator.bootstrap.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.bootstrap.observability.LoggedBreakerTransitions;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.web.client.RestClient;

class ProvidersConfigTest {

  private static final LegKey FLIGHT =
      new LegKey(
          new Carrier("IB"),
          3170,
          new Airport("MAD"),
          new Airport("LHR"),
          Instant.parse("2026-11-20T07:15:00Z"));

  private static final Airport MADRID = new Airport("MAD");

  private static final String ALPHA_LAST_PAGE = "{\"legs\":[],\"nextCursor\":null}";

  private static final String BETA_NONE =
      "<SegmentsResponse page=\"1\" totalPages=\"1\"><Segments/></SegmentsResponse>";

  /**
   * The endpoints only, with Boot's AOP defaults, as in the application: they decide how the
   * providers are proxied.
   */
  private final ApplicationContextRunner withoutLimit =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
          .withUserConfiguration(ProvidersConfig.class, SystemClock.class, TracingConfig.class)
          .withBean(RestClient.Builder.class, RestClient::builder)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues(
              "providers.alpha.base-url=http://alpha.test/",
              "providers.alpha.api-key=alpha-key",
              "providers.beta.base-url=http://beta.test/",
              "providers.beta.api-key=beta-key",
              "providers.gamma.base-url=http://gamma.test/",
              "providers.gamma.api-key=gamma-key");

  /** The rest of the settings from the application's own {@code application.yaml}. */
  private final ApplicationContextRunner runner =
      withoutLimit.withInitializer(new ConfigDataApplicationContextInitializer());

  private final HeldOriginCalls held = new HeldOriginCalls();

  private final WireMockServer server =
      new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(held));

  @BeforeEach
  void startServer() {
    server.start();
  }

  @AfterEach
  void stopServer() {
    held.release();
    server.stop();
  }

  /**
   * Every provider of the closed set is wired, each from its own endpoint, which names it once: a
   * mis-wired provider's failures would be reported under another name.
   */
  @Test
  void buildsEachProviderFromItsOwnEndpoint() {
    runner.run(
        context -> {
          assertThat(providers(context))
              .extracting(FlightProvider::provider)
              .containsExactly(Provider.values());
          ProvidersProperties properties = context.getBean(ProvidersProperties.class);
          for (Provider provider : Provider.values()) {
            String lower = provider.name().toLowerCase(Locale.ROOT);
            ProviderEndpoint endpoint = properties.endpoint(provider);
            assertThat(endpoint.provider()).isEqualTo(provider);
            assertThat(endpoint.baseUrl()).hasToString("http://" + lower + ".test/");
            assertThat(endpoint.apiKey()).isEqualTo(lower + "-key");
          }
        });
  }

  /** A misspelt setting stops startup, instead of leaving the default it meant to change. */
  @Test
  void refusesAnUnknownProviderSetting() {
    runner
        .withPropertyValues("providers.download-timout=45s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("providers.download-timout");
            });
  }

  /**
   * Without the limit, every provider call would fail at its first use, while the application
   * looked healthy; so startup stops instead, naming the setting.
   */
  @Test
  void refusesToStartWithoutAConcurrencyLimit() {
    withoutLimit.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("providers.concurrency-limit");
        });
  }

  /** 0 would fail every call, and -1 would remove the limit without a word. */
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void refusesAConcurrencyLimitBelowOne(int limit) {
    runner
        .withPropertyValues("providers.concurrency-limit=" + limit)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("providers.concurrency-limit");
            });
  }

  @Test
  void takesEachResilienceSettingFromItsProperty() {
    runner
        .withPropertyValues(
            "providers.request-timeout=1500ms",
            "providers.download-timeout=45s",
            "providers.max-attempts=4",
            "providers.page-size=500",
            "providers.max-pages=50")
        .run(
            context ->
                assertThat(context.getBean(ProvidersProperties.class).resilience())
                    .isEqualTo(
                        new ResilienceSettings(
                            Duration.ofMillis(1500),
                            Duration.ofSeconds(45),
                            4,
                            Duration.ofMillis(200),
                            500,
                            50,
                            8 * 1024 * 1024)));
  }

  /** The plan's values: 2.5 s per request, 60 s per download, 3 attempts, 1000 records a page. */
  @Test
  void defaultsToTheProductionResilienceSettings() {
    runner.run(
        context ->
            assertThat(context.getBean(ProvidersProperties.class).resilience())
                .isEqualTo(
                    new ResilienceSettings(
                        Duration.ofMillis(2500),
                        Duration.ofSeconds(60),
                        3,
                        Duration.ofMillis(200),
                        1000,
                        200,
                        8 * 1024 * 1024)));
  }

  /**
   * Each provider's calls open only its own breaker, named after the provider, with the production
   * configuration.
   */
  @Test
  void givesEachProviderItsOwnBreakerNamedAfterIt() {
    runner.run(
        context -> {
          List<CircuitBreaker> breakers =
              List.copyOf(context.getBean(CircuitBreakerRegistry.class).getAllCircuitBreakers());
          assertThat(breakers)
              .extracting(CircuitBreaker::getName)
              .containsExactlyInAnyOrder("ALPHA", "BETA", "GAMMA");
          assertThat(breakers)
              .extracting(CircuitBreaker::getCircuitBreakerConfig)
              .containsOnly(ProviderHttp.circuitBreakerConfig());
        });
  }

  /** Each provider's breaker is seen in the metrics (§6.10), under the provider's name. */
  @Test
  void measuresEachBreakersState() {
    runner.run(
        context -> {
          MeterRegistry meters = context.getBean(MeterRegistry.class);
          for (Provider provider : Provider.values()) {
            assertThat(
                    meters
                        .get("resilience4j.circuitbreaker.state")
                        .tag("name", provider.name())
                        .tag("state", "closed")
                        .gauge()
                        .value())
                .as(provider.name())
                .isEqualTo(1);
          }
        });
  }

  /** A breaker that opens, or probes again, is one warning naming the provider and both states. */
  @Test
  void warnsOnEachStateTransitionOfABreaker() {
    Logger logger = (Logger) LoggerFactory.getLogger(LoggedBreakerTransitions.class);
    ListAppender<ILoggingEvent> log = new ListAppender<>();
    log.start();
    logger.addAppender(log);
    try {
      runner.run(
          context -> {
            CircuitBreaker beta =
                context.getBean(CircuitBreakerRegistry.class).circuitBreaker("BETA");

            beta.transitionToOpenState();
            beta.transitionToHalfOpenState();
          });
    } finally {
      logger.detachAppender(log);
    }

    assertThat(log.list)
        .extracting(ILoggingEvent::getLevel, ProvidersConfigTest::fields)
        .containsExactly(
            tuple(Level.WARN, "provider=BETA from=CLOSED to=OPEN"),
            tuple(Level.WARN, "provider=BETA from=OPEN to=HALF_OPEN"));
  }

  /**
   * Q9: at most {@code concurrency-limit} calls of a provider at once, its origin downloads and
   * quotes sharing the limit; the call over it is rejected, not queued, and another provider's
   * calls go on.
   */
  @Test
  void limitsConcurrentCallsPerProvider() {
    server.stubFor(
        get(urlPathEqualTo("/beta/segments")).willReturn(aResponse().withBody(BETA_NONE)));
    limitedTo(2)
        .run(
            context -> {
              FlightProvider alpha = provider(context, Provider.ALPHA);
              FlightProvider beta = provider(context, Provider.BETA);
              List<CompletableFuture<Void>> calls =
                  inFlight(
                      () -> alpha.downloadFrom(new Airport("MAD")),
                      () -> alpha.downloadFrom(new Airport("BCN")));

              assertThatThrownBy(() -> alpha.quote(FLIGHT))
                  .isInstanceOf(InvocationRejectedException.class);
              assertThat(beta.quote(FLIGHT)).isEmpty();

              letThemEnd(calls);
              assertThat(alphaRequests()).isEqualTo(2);
            });
  }

  /**
   * The run's download is outside the limit (plan 5's end-of-plan review, I2): with every call it
   * allows taken by refreshes and re-prices, a run's download still goes out, so they never make a
   * run keep a healthy provider's old offers.
   */
  @Test
  void neverRejectsTheRunsDownloadWhileTheLimitIsExhausted() {
    limitedTo(1)
        .run(
            context -> {
              FlightProvider alpha = provider(context, Provider.ALPHA);
              List<CompletableFuture<Void>> calls = inFlight(() -> alpha.downloadFrom(MADRID));

              assertThatCode(alpha::download).doesNotThrowAnyException();

              assertThat(calls).noneMatch(CompletableFuture::isDone);
              letThemEnd(calls);
            });
  }

  /** A caller finds a provider by {@code provider()}, so the limit must never reject it. */
  @Test
  void answersItsProviderWhileItsLimitIsExhausted() {
    limitedTo(1)
        .run(
            context -> {
              FlightProvider alpha = provider(context, Provider.ALPHA);
              List<CompletableFuture<Void>> calls = inFlight(() -> alpha.downloadFrom(MADRID));

              assertThat(alpha.provider()).isEqualTo(Provider.ALPHA);

              letThemEnd(calls);
            });
  }

  /** A provider printed in a log line or a message while its limit is exhausted. */
  @Test
  void printsItselfWhileItsLimitIsExhausted() {
    limitedTo(1)
        .run(
            context -> {
              FlightProvider alpha = provider(context, Provider.ALPHA);
              List<CompletableFuture<Void>> calls = inFlight(() -> alpha.downloadFrom(MADRID));

              assertThat(alpha.toString()).contains("ALPHA");

              letThemEnd(calls);
            });
  }

  /** The count wraps the limit, so a call the limit rejects is counted too (§6.10). */
  @Test
  void countsARejectedCall() {
    limitedTo(1)
        .run(
            context -> {
              FlightProvider alpha = provider(context, Provider.ALPHA);
              List<CompletableFuture<Void>> calls = inFlight(() -> alpha.downloadFrom(MADRID));

              assertThatThrownBy(() -> alpha.quote(FLIGHT))
                  .isInstanceOf(InvocationRejectedException.class);
              letThemEnd(calls);

              MeterRegistry meters = context.getBean(MeterRegistry.class);
              assertThat(calls(meters, "quote", "REJECTED")).isEqualTo(1);
              assertThat(calls(meters, "origin-download", "OK")).isEqualTo(1);
            });
  }

  /**
   * Q9 as the application configures it: its {@code application.yaml} lets 4 calls of a provider
   * run at once, and rejects a fifth.
   */
  @Test
  void limitsEachProviderToTheApplicationsFourCalls() {
    busy(runner)
        .run(
            context -> {
              FlightProvider alpha = provider(context, Provider.ALPHA);
              Runnable fromMadrid = () -> alpha.downloadFrom(MADRID);
              List<CompletableFuture<Void>> calls =
                  inFlight(fromMadrid, fromMadrid, fromMadrid, fromMadrid);

              assertThatThrownBy(fromMadrid::run).isInstanceOf(InvocationRejectedException.class);

              letThemEnd(calls);
            });
  }

  /** As {@link #busy}, under a limit of {@code limit} calls per provider. */
  private ApplicationContextRunner limitedTo(int limit) {
    return busy(runner.withPropertyValues("providers.concurrency-limit=" + limit));
  }

  /**
   * Every provider at the WireMock server, Alpha's calls for an origin held until {@link
   * #letThemEnd}, so that each holds its share of the limit meanwhile. A held request waits longer
   * than any test, not Alpha's 2.5 s request timeout.
   */
  private ApplicationContextRunner busy(ApplicationContextRunner providers) {
    server.stubFor(
        get(urlPathEqualTo("/alpha/legs")).willReturn(aResponse().withBody(ALPHA_LAST_PAGE)));
    held.arm();
    return providers
        .withPropertyValues(against(server))
        .withPropertyValues("providers.request-timeout=30s");
  }

  /** Lets the held calls answer, and waits for them to end. */
  private void letThemEnd(List<CompletableFuture<Void>> calls) {
    held.release();
    calls.forEach(CompletableFuture::join);
  }

  /**
   * Once armed, holds every Alpha request for an origin, an origin download or a quote, until
   * released; a whole download is never held. WireMock journals a request before it calls this
   * listener, so a test sees each held call arrive, then checks the limit while it is still taken.
   */
  private static final class HeldOriginCalls implements ServeEventListener {

    private final CountDownLatch released = new CountDownLatch(1);
    private volatile boolean armed;

    @Override
    public String getName() {
      return "held-origin-calls";
    }

    @Override
    public void beforeResponseSent(ServeEvent event, Parameters parameters) {
      if (armed
          && event.getRequest().getUrl().startsWith("/alpha/")
          && event.getRequest().queryParameter("origin").isPresent()) {
        try {
          released.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    void arm() {
      armed = true;
    }

    void release() {
      released.countDown();
    }
  }

  /**
   * Starts each call on a thread of its own, never queued behind another, and returns once Alpha
   * has received all of them.
   */
  private List<CompletableFuture<Void>> inFlight(Runnable... calls) {
    List<CompletableFuture<Void>> started =
        Stream.of(calls)
            .map(call -> CompletableFuture.runAsync(call, task -> Thread.ofVirtual().start(task)))
            .toList();
    await().atMost(Duration.ofSeconds(5)).until(() -> alphaRequests() == calls.length);
    return started;
  }

  private static double calls(MeterRegistry meters, String operation, String outcome) {
    return meters
        .get("provider.calls")
        .tags("provider", "ALPHA", "operation", operation, "outcome", outcome)
        .counter()
        .count();
  }

  private int alphaRequests() {
    return server.findAll(getRequestedFor(urlPathEqualTo("/alpha/legs"))).size();
  }

  /** Every provider at the WireMock server. */
  private static String[] against(WireMockServer server) {
    return Stream.of(Provider.values())
        .map(provider -> "providers." + provider.name().toLowerCase(Locale.ROOT) + ".base-url=")
        .map(property -> property + server.baseUrl())
        .toArray(String[]::new);
  }

  private static FlightProvider provider(ApplicationContext context, Provider provider) {
    return providers(context).get(provider.ordinal());
  }

  private static String fields(ILoggingEvent event) {
    return String.join(
        " ", event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList());
  }

  /** The wired providers, in {@code Provider} order. */
  private static List<FlightProvider> providers(ApplicationContext context) {
    return context.getBeansOfType(FlightProvider.class).values().stream()
        .sorted(Comparator.comparing(FlightProvider::provider))
        .toList();
  }
}
