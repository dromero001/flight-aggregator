package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.adapter.provider.ProviderEndpoint;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.bootstrap.observability.LoggedBreakerTransitions;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

class ProvidersConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ProvidersConfig.class, SystemClock.class)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues(
              "providers.alpha.base-url=http://alpha.test/",
              "providers.alpha.api-key=alpha-key",
              "providers.beta.base-url=http://beta.test/",
              "providers.beta.api-key=beta-key",
              "providers.gamma.base-url=http://gamma.test/",
              "providers.gamma.api-key=gamma-key");

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
