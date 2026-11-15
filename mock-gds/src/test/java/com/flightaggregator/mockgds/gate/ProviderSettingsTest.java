package com.flightaggregator.mockgds.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Provider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;

class ProviderSettingsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Settings.class);

  @EnableConfigurationProperties(ProviderSettings.class)
  static class Settings {}

  @Test
  void failsToStartWithoutAnApiKey() {
    runner
        .withPropertyValues("mockgds.providers.alpha.api-key=a", "mockgds.providers.beta.api-key=b")
        .run(context -> assertThat(context).getFailure().hasStackTraceContaining("gamma.api-key"));
  }

  private static final String[] KEYS = {
    "mockgds.providers.alpha.api-key=a",
    "mockgds.providers.beta.api-key=b",
    "mockgds.providers.gamma.api-key=g"
  };

  @Test
  void rejectsALatencyMinimumAboveTheMaximum() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues(
            "mockgds.providers.beta.fast-latency-min=2s",
            "mockgds.providers.beta.fast-latency-max=1s")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("beta.fast-latency-min"));
  }

  @Test
  void rejectsASlowLatencyMinimumAboveItsMaximum() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues(
            "mockgds.providers.beta.slow-latency-min=6s",
            "mockgds.providers.beta.slow-latency-max=5s")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("beta.slow-latency-min"));
  }

  @Test
  void rejectsASlowRangeStartingBeforeTheFastRangeEnds() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.alpha.fast-latency-max=2s")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("alpha.fast-latency-max"));
  }

  @Test
  void rejectsAnErrorRateOutsideZeroToOne() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.gamma.error-rate=1.5")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("gamma.error-rate"));
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.alpha.error-rate=-0.1")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("alpha.error-rate"));
  }

  @Test
  void rejectsASlowShareOutsideZeroToOne() {
    for (var share : new String[] {"1.5", "-0.1", "NaN"}) {
      runner
          .withPropertyValues(KEYS)
          .withPropertyValues("mockgds.providers.gamma.slow-share=" + share)
          .run(
              context ->
                  assertThat(context)
                      .as(share)
                      .getFailure()
                      .hasStackTraceContaining("gamma.slow-share"));
    }
  }

  @Test
  void rejectsANegativeLatency() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.alpha.fast-latency-min=-1ms")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("alpha.fast-latency-min"));
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

  @Test
  void rejectsAnErrorRateThatIsNotANumber() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.beta.error-rate=NaN")
        .run(
            context -> assertThat(context).getFailure().hasStackTraceContaining("beta.error-rate"));
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
