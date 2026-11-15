package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.ValkeyImage;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.port.out.SearchKey;
import com.flightaggregator.domain.Airport;
import com.redis.testcontainers.RedisContainer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.lettuce.core.ClientOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

class ValkeyConfigTest {

  private static final Airport MADRID = new Airport("MAD");

  private static final SearchKey KEY =
      new SearchKey(
          1,
          new SearchCriteria(
              Optional.of(MADRID),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              SortOrder.PRICE),
          20);

  private static final SearchPage NOTHING_FOUND =
      new SearchPage(List.of(), Optional.empty(), false);

  @SuppressWarnings("resource") // Stopped by Testcontainers' Ryuk when the JVM ends.
  private static final RedisContainer VALKEY =
      new RedisContainer(DockerImageName.parse(ValkeyImage.NAME));

  /** The search settings the application sets. */
  private static final Map<String, String> SEARCH_SETTINGS =
      Map.of(
          "search.cache-ttl", "PT5M",
          "search.freshness-threshold", "PT10M",
          "search.refresh-claim-ttl", "PT2M",
          "search.valkey-skip", "PT10S");

  /** The Valkey stores over Boot's Lettuce client, with no setting of the application's. */
  private final ApplicationContextRunner withoutSettings =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
          .withUserConfiguration(ValkeyConfig.class)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

  /** The application's own settings, from its {@code application.yaml}. */
  private final ApplicationContextRunner runner =
      withoutSettings.withInitializer(new ConfigDataApplicationContextInitializer());

  @BeforeAll
  static void startValkey() {
    VALKEY.start();
  }

  /**
   * Each store gets its own setting from {@code application.yaml}: pages kept 5 min (§6.4), refresh
   * claims 2 min (Q9) and refresh marks 10 min, the freshness threshold (§6.4).
   */
  @Test
  void wiresEachStoreWithItsSetting() {
    runner
        .withPropertyValues(
            "spring.data.redis.host=" + VALKEY.getRedisHost(),
            "spring.data.redis.port=" + VALKEY.getRedisPort())
        .run(
            context -> {
              StringRedisTemplate valkey = context.getBean(StringRedisTemplate.class);
              RefreshCoordination coordination = context.getBean(RefreshCoordination.class);

              context.getBean(SearchCache.class).put(KEY, NOTHING_FOUND);
              coordination.claim(MADRID);
              coordination.refreshed(MADRID, Instant.parse("2026-11-20T10:00:00Z"));

              assertThat(valkey.getExpire(KEY.value(), TimeUnit.SECONDS)).isBetween(295L, 300L);
              assertThat(valkey.getExpire("refresh:MAD", TimeUnit.SECONDS)).isBetween(115L, 120L);
              assertThat(valkey.getExpire("fresh:MAD", TimeUnit.SECONDS)).isBetween(595L, 600L);
              assertThat(
                      context
                          .getBean(RequestCounter.class)
                          .increment("search:203.0.113.7", Duration.ofMinutes(1))
                          .count())
                  .isEqualTo(1);
            });
  }

  /**
   * The stores share one circuit breaker, which skips Valkey for {@code search.valkey-skip} after a
   * failed command: while it is open, a page Valkey holds is not read.
   */
  @Test
  void skipsValkeyForItsSettingThroughTheStoresBreaker() {
    runner
        .withPropertyValues(
            "spring.data.redis.host=" + VALKEY.getRedisHost(),
            "spring.data.redis.port=" + VALKEY.getRedisPort(),
            "search.valkey-skip=PT3S")
        .run(
            context -> {
              CircuitBreaker breaker = context.getBean(CircuitBreaker.class);
              SearchCache cache = context.getBean(SearchCache.class);
              cache.put(KEY, NOTHING_FOUND);

              breaker.transitionToOpenState();

              assertThat(context.getBean(StringRedisTemplate.class).hasKey(KEY.value())).isTrue();
              assertThat(cache.get(KEY)).isEmpty();
              assertThat(
                      breaker
                          .getCircuitBreakerConfig()
                          .getWaitIntervalFunctionInOpenState()
                          .apply(1))
                  .isEqualTo(3_000L);
            });
  }

  /**
   * Q9: a command waits 250 ms at most and a connection 1 s, instead of Lettuce's 60 s and 10 s, so
   * Valkey down costs a search little (Review Focus 3).
   */
  @Test
  void boundsEachCommandAndConnectionAsQ9Says() {
    runner.run(
        context -> {
          LettuceClientConfiguration client =
              context.getBean(LettuceConnectionFactory.class).getClientConfiguration();

          assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(250));
          assertThat(client.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
              .isEqualTo(Duration.ofSeconds(1));
        });
  }

  /**
   * The user's decision of 2026-11-15: after a failed command, Valkey is skipped for 10 s, then
   * probed.
   */
  @Test
  void skipsValkeyTenSecondsAfterAFailedCommand() {
    runner.run(
        context ->
            assertThat(
                    context
                        .getBean(CircuitBreaker.class)
                        .getCircuitBreakerConfig()
                        .getWaitIntervalFunctionInOpenState()
                        .apply(1))
                .isEqualTo(10_000L));
  }

  /**
   * Q9: while Lettuce has lost its connection, a command fails at once, instead of queueing until
   * Lettuce reconnects or the command times out.
   */
  @Test
  void rejectsCommandsWhileDisconnected() {
    runner.run(
        context ->
            assertThat(
                    context
                        .getBean(LettuceConnectionFactory.class)
                        .getClientConfiguration()
                        .getClientOptions()
                        .orElseThrow()
                        .getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS));
  }

  /**
   * Without one of search's settings, startup stops and names it, instead of the application
   * starting with a store that fails or misbehaves at its first use.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "search.cache-ttl",
        "search.freshness-threshold",
        "search.refresh-claim-ttl",
        "search.valkey-skip"
      })
  void refusesToStartWithoutASearchSetting(String missing) {
    withoutSettings
        .withPropertyValues(
            SEARCH_SETTINGS.entrySet().stream()
                .filter(setting -> !setting.getKey().equals(missing))
                .map(setting -> setting.getKey() + "=" + setting.getValue())
                .toArray(String[]::new))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining(missing);
            });
  }

  /** A misspelt setting stops startup, instead of leaving the value it meant to change. */
  @Test
  void refusesAnUnknownSearchSetting() {
    runner
        .withPropertyValues("search.cache-tll=PT1M")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining("search.cache-tll");
            });
  }

  /**
   * A zero or negative time would make Valkey refuse every write that sets it, which the stores
   * would take for an outage, so startup stops instead.
   */
  @ParameterizedTest
  @CsvSource({
    "search.cache-ttl, PT0S",
    "search.freshness-threshold, -PT10M",
    "search.refresh-claim-ttl, PT0S",
    "search.valkey-skip, -PT10S"
  })
  void refusesASearchSettingThatIsNotPositive(String setting, String value) {
    runner
        .withPropertyValues(setting + "=" + value)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining(setting);
            });
  }
}
