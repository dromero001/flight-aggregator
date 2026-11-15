package com.flightaggregator.bootstrap.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

class ProvidersConfigTest {

  @RegisterExtension
  static final WireMockExtension providers =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ProvidersConfig.class, SystemClock.class)
          .withPropertyValues(
              "providers.alpha.base-url=http://alpha.test/",
              "providers.alpha.api-key=alpha-key",
              "providers.beta.base-url=http://beta.test/",
              "providers.beta.api-key=beta-key",
              "providers.gamma.base-url=http://gamma.test/",
              "providers.gamma.api-key=gamma-key");

  /**
   * One factory method derives every part from the provider, so no part of one provider can be
   * wired to another's: a mis-wired provider's failures would be reported under another name.
   */
  @Test
  void buildsEachProviderFromItsOwnSettings() {
    runner
        .withPropertyValues("providers.download-timeout=45s")
        .run(
            context -> {
              List<WiredProvider> wired = wiredProviders(context);

              assertThat(wired)
                  .extracting(provider -> provider.adapter().provider())
                  .containsExactly(Provider.ALPHA, Provider.BETA, Provider.GAMMA);
              for (WiredProvider provider : wired) {
                Provider name = provider.adapter().provider();
                String lower = name.name().toLowerCase(Locale.ROOT);
                assertThat(provider.endpoint().provider()).isEqualTo(name);
                assertThat(provider.endpoint().baseUrl()).hasToString("http://" + lower + ".test/");
                assertThat(provider.endpoint().apiKey()).isEqualTo(lower + "-key");
                assertThat(providerOf(provider.download())).isEqualTo(name);
                TimeLimiterConfig budget = provider.timeLimiter().getTimeLimiterConfig();
                assertThat(budget.getTimeoutDuration()).isEqualTo(Duration.ofSeconds(45));
                assertThat(budget.shouldCancelRunningFuture()).isTrue();
              }
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
                assertThat(wiredProviders(context))
                    .extracting(WiredProvider::settings)
                    .containsOnly(
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
            assertThat(wiredProviders(context))
                .extracting(WiredProvider::settings)
                .containsOnly(
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
   * A server error counts against the breaker, and a refused key or a body over the limit neither
   * counts as a failure nor as a success, since no wait heals them.
   */
  @Test
  void breakerRecordsAServerErrorAndIgnoresARefusedKeyOrAnOversizedBody() {
    providers.stubFor(get(urlPathEqualTo("/alpha/legs")).willReturn(aResponse().withStatus(500)));
    providers.stubFor(
        get(urlPathEqualTo("/beta/segments")).willReturn(aResponse().withStatus(401)));
    providers.stubFor(
        get(urlPathEqualTo("/gamma/legs.csv"))
            .willReturn(aResponse().withBody("x".repeat(8 * 1024 * 1024 + 1))));

    runner
        .withPropertyValues(
            "providers.alpha.base-url=" + providers.baseUrl(),
            "providers.beta.base-url=" + providers.baseUrl(),
            "providers.gamma.base-url=" + providers.baseUrl())
        .run(
            context -> {
              List<WiredProvider> wired = wiredProviders(context);
              wired.forEach(provider -> catchThrowable(() -> provider.adapter().download()));

              assertThat(wired)
                  .extracting(
                      provider -> provider.adapter().provider(),
                      provider -> provider.circuitBreaker().getMetrics().getNumberOfFailedCalls(),
                      provider ->
                          provider.circuitBreaker().getMetrics().getNumberOfSuccessfulCalls())
                  .containsExactly(
                      tuple(Provider.ALPHA, 3, 0),
                      tuple(Provider.BETA, 0, 0),
                      tuple(Provider.GAMMA, 0, 0));
            });
  }

  /** The plan's window: the last 10 calls, open at 50% failures for 60 s, then 3 trial calls. */
  @Test
  void givesEachProviderItsOwnBreakerWithTheProductionWindow() {
    runner.run(
        context -> {
          List<WiredProvider> wired = wiredProviders(context);

          assertThat(wired)
              .extracting(provider -> provider.circuitBreaker().getName())
              .containsExactly("ALPHA", "BETA", "GAMMA");
          for (WiredProvider provider : wired) {
            CircuitBreakerConfig config = provider.circuitBreaker().getCircuitBreakerConfig();
            assertThat(config.getSlidingWindowType()).isEqualTo(SlidingWindowType.COUNT_BASED);
            assertThat(config.getSlidingWindowSize()).isEqualTo(10);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50);
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(60_000L);
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
          }
        });
  }

  /** The wired providers, in {@code Provider} order. */
  private static List<WiredProvider> wiredProviders(ApplicationContext context) {
    return context.getBeansOfType(WiredProvider.class).values().stream()
        .sorted(Comparator.comparing(provider -> provider.adapter().provider()))
        .toList();
  }

  /** The provider a download names in its failures: a position that repeats fails it at once. */
  private static Provider providerOf(PagedDownload download) {
    ProviderUnavailableException failure =
        catchThrowableOfType(
            ProviderUnavailableException.class,
            () ->
                download.download(
                    position -> new ParsedPage(List.of(), List.of(), Optional.of("same"))));
    assertThat(failure.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT);
    return failure.provider();
  }
}
