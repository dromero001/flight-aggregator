package com.flightaggregator.mockgds.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.mockgds.model.Provider;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ProviderSettingsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SettingsConfig.class);

  private static final String[] KEYS = {
    "mockgds.providers.alpha.api-key=a",
    "mockgds.providers.beta.api-key=b",
    "mockgds.providers.gamma.api-key=g"
  };

  /**
   * An entry validates itself, so even one built in code cannot be invalid. Each row's message
   * names the rule that fired. Columns: message, key, fast min, fast max, slow min, slow max (ms),
   * slow share, error rate.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          api-key must be set                                            |      | 100  | 1000 | 1000 | 5000 | 0.1  | 0.05
          api-key must be set                                            | '  ' | 100  | 1000 | 1000 | 5000 | 0.1  | 0.05
          fast-latency-min must not be negative: PT-0.001S               | k    | -1   | 1000 | 1000 | 5000 | 0.1  | 0.05
          fast-latency-min must not exceed fast-latency-max: PT2S > PT1S | k    | 2000 | 1000 | 1000 | 5000 | 0.1  | 0.05
          slow-latency-min must not be negative: PT-0.001S               | k    | 100  | 1000 | -1   | 5000 | 0.1  | 0.05
          slow-latency-min must not exceed slow-latency-max: PT6S > PT5S | k    | 100  | 1000 | 6000 | 5000 | 0.1  | 0.05
          slow-share must be within [0, 1]: 1.5                          | k    | 100  | 1000 | 1000 | 5000 | 1.5  | 0.05
          slow-share must be within [0, 1]: -0.1                         | k    | 100  | 1000 | 1000 | 5000 | -0.1 | 0.05
          slow-share must be within [0, 1]: NaN                          | k    | 100  | 1000 | 1000 | 5000 | NaN  | 0.05
          error-rate must be within [0, 1]: 1.5                          | k    | 100  | 1000 | 1000 | 5000 | 0.1  | 1.5
          error-rate must be within [0, 1]: -0.1                         | k    | 100  | 1000 | 1000 | 5000 | 0.1  | -0.1
          error-rate must be within [0, 1]: NaN                          | k    | 100  | 1000 | 1000 | 5000 | 0.1  | NaN
          """)
  void rejectsAnInvalidEntryNamingThePropertyAndTheRule(
      String message,
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
        .hasMessage(message);
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

  /** A misspelt or outdated property stops startup instead of silently leaving its default on. */
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

  /**
   * Boot leaves the system environment out of the unknown-key check (its
   * UnboundElementsSourceFilter holds the source benign, since it carries every variable of the
   * machine), so the mock checks the variables under its prefix itself. The failure names them,
   * never their values.
   */
  @Test
  void failsToStartOnAnUnknownEnvironmentVariableNamingItButNotItsValue() {
    runner
        .withPropertyValues(KEYS)
        .withInitializer(
            withEnvironment(
                Map.of(
                    "MOCKGDS_PROVIDERS_ALPHA_LATENCYMIN", "0ms",
                    "MOCKGDS_PROVIDERS_BETA_APIKYE", "secret-beta")))
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessage(
                        "Environment variables that name no provider setting:"
                            + " [MOCKGDS_PROVIDERS_ALPHA_LATENCYMIN, MOCKGDS_PROVIDERS_BETA_APIKYE]"));
  }

  /** The names the README documents, one per setting and provider, all bind. */
  @Test
  void startsWithEveryDocumentedEnvironmentVariable() {
    var values =
        Map.of(
            "APIKEY", "k",
            "FASTLATENCYMIN", "0ms",
            "FASTLATENCYMAX", "0ms",
            "SLOWLATENCYMIN", "0ms",
            "SLOWLATENCYMAX", "0ms",
            "SLOWSHARE", "0",
            "ERRORRATE", "0");
    var variables = new HashMap<String, Object>();
    for (var provider : List.of("ALPHA", "BETA", "GAMMA")) {
      values.forEach(
          (setting, value) ->
              variables.put("MOCKGDS_PROVIDERS_" + provider + "_" + setting, value));
    }
    runner
        .withInitializer(withEnvironment(variables))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var settings = context.getBean(ProviderSettings.class);
              for (var provider : Provider.values()) {
                assertThat(settings.forProvider(provider).errorRate()).isZero();
              }
            });
  }

  /** Replaces the machine's environment with these variables, under the real source's name. */
  private static ApplicationContextInitializer<ConfigurableApplicationContext> withEnvironment(
      Map<String, Object> variables) {
    var environment =
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables);
    return context ->
        context.getEnvironment().getPropertySources().replace(environment.getName(), environment);
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
