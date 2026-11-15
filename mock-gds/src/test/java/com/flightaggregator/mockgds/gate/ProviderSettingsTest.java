package com.flightaggregator.mockgds.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.mockgds.model.Provider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;

class ProviderSettingsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Settings.class);

  @EnableConfigurationProperties(ProviderSettings.class)
  static class Settings {}

  private static final String[] KEYS = {
    "mockgds.providers.alpha.api-key=a",
    "mockgds.providers.beta.api-key=b",
    "mockgds.providers.gamma.api-key=g"
  };

  /** An entry validates itself, so even one built in code cannot be invalid. */
  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
    // property, why, key, fast min, fast max, slow min, slow max (ms), slow share, error rate
    "api-key, missing, , 100, 1000, 1000, 5000, 0.1, 0.05",
    "api-key, blank, '  ', 100, 1000, 1000, 5000, 0.1, 0.05",
    "fast-latency-min, negative, k, -1, 1000, 1000, 5000, 0.1, 0.05",
    "fast-latency-min, above the maximum, k, 2000, 1000, 1000, 5000, 0.1, 0.05",
    "slow-latency-min, negative, k, 100, 1000, -1, 5000, 0.1, 0.05",
    "slow-latency-min, above the maximum, k, 100, 1000, 6000, 5000, 0.1, 0.05",
    "slow-share, above one, k, 100, 1000, 1000, 5000, 1.5, 0.05",
    "slow-share, negative, k, 100, 1000, 1000, 5000, -0.1, 0.05",
    "slow-share, not a number, k, 100, 1000, 1000, 5000, NaN, 0.05",
    "error-rate, above one, k, 100, 1000, 1000, 5000, 0.1, 1.5",
    "error-rate, negative, k, 100, 1000, 1000, 5000, 0.1, -0.1",
    "error-rate, not a number, k, 100, 1000, 1000, 5000, 0.1, NaN"
  })
  void rejectsAnInvalidEntryNamingTheProperty(
      String property,
      String why,
      String apiKey,
      long fastMin,
      long fastMax,
      long slowMin,
      long slowMax,
      double slowShare,
      double errorRate) {
    assertThatThrownBy(
            () ->
                new ProviderSettings.Entry(
                    apiKey,
                    Duration.ofMillis(fastMin),
                    Duration.ofMillis(fastMax),
                    Duration.ofMillis(slowMin),
                    Duration.ofMillis(slowMax),
                    slowShare,
                    errorRate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(property + " ");
  }

  @Test
  void failsToStartWithoutAnApiKey() {
    runner
        .withPropertyValues("mockgds.providers.alpha.api-key=a", "mockgds.providers.beta.api-key=b")
        .run(context -> assertThat(context).getFailure().hasStackTraceContaining("gamma.api-key"));
  }

  /** The binder names the provider and the entry names the property, so a bad value says where. */
  @Test
  void failsToStartOnAnInvalidSettingNamingItsProviderAndProperty() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues(
            "mockgds.providers.beta.fast-latency-min=2s",
            "mockgds.providers.beta.fast-latency-max=1s")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasStackTraceContaining("'mockgds.providers.beta'")
                    .hasStackTraceContaining("fast-latency-min must not exceed fast-latency-max"));
  }

  /** The two ranges may overlap: the gate draws from one of them, whatever their order. */
  @Test
  void startsWithAFastRangeAboveTheSlowMinimum() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues(
            "mockgds.providers.alpha.fast-latency-min=2s",
            "mockgds.providers.alpha.fast-latency-max=2s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var alpha = context.getBean(ProviderSettings.class).alpha();
              assertThat(alpha.fastLatencyMax()).isEqualTo(Duration.ofSeconds(2));
              assertThat(alpha.slowLatencyMin()).isEqualTo(Duration.ofSeconds(1));
            });
  }

  @Test
  void neverPrintsTheApiKey() {
    runner
        .withPropertyValues(
            "mockgds.providers.alpha.api-key=secret-alpha",
            "mockgds.providers.beta.api-key=secret-beta",
            "mockgds.providers.gamma.api-key=secret-gamma")
        .run(
            context -> {
              var settings = context.getBean(ProviderSettings.class);
              assertThat(settings.toString()).doesNotContain("secret-");
              assertThat(settings.alpha().toString()).doesNotContain("secret-alpha");
            });
  }

  /** A misspelt or outdated setting stops startup instead of silently leaving its default on. */
  @Test
  void failsToStartOnAnUnknownSetting() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.alpha.latency-min=0ms")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasStackTraceContaining("mockgds.providers.alpha.latency-min"));
  }

  /** The same through an environment variable, the way compose passes the settings. */
  @Test
  void failsToStartOnAnUnknownSettingFromTheEnvironment() {
    runner
        .withPropertyValues(KEYS)
        .withInitializer(
            context ->
                TestPropertyValues.of("MOCKGDS_PROVIDERS_ALPHA_LATENCYMIN=0ms")
                    .applyTo(context.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT))
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasStackTraceContaining("mockgds.providers.alpha.latencymin"));
  }

  @Test
  void defaultsTheLatencyRangesTheSlowShareAndTheErrorRate() {
    runner
        .withPropertyValues(KEYS)
        .run(
            context -> {
              var settings = context.getBean(ProviderSettings.class);
              for (var provider : Provider.values()) {
                var entry = settings.forProvider(provider);
                assertThat(entry.fastLatencyMin()).isEqualTo(Duration.ofMillis(100));
                assertThat(entry.fastLatencyMax()).isEqualTo(Duration.ofMillis(1000));
                assertThat(entry.slowLatencyMin()).isEqualTo(Duration.ofMillis(1000));
                assertThat(entry.slowLatencyMax()).isEqualTo(Duration.ofMillis(5000));
                assertThat(entry.slowShare()).isEqualTo(0.1);
                assertThat(entry.errorRate()).isEqualTo(0.05);
              }
            });
  }
}
