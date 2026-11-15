package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ParsedPage;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

class ProvidersConfigTest {

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
   * Every provider of the closed set is wired, and one factory method derives every part from the
   * provider, so no part of one provider can be wired to another's: a mis-wired provider's failures
   * would be reported under another name.
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
                  .containsExactly(Provider.values());
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

  /** Each provider's calls open only its own breaker, named after the provider. */
  @Test
  void givesEachProviderItsOwnBreakerNamedAfterIt() {
    runner.run(
        context ->
            assertThat(wiredProviders(context))
                .allSatisfy(
                    provider ->
                        assertThat(provider.circuitBreaker().getName())
                            .isEqualTo(provider.adapter().provider().name())));
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
