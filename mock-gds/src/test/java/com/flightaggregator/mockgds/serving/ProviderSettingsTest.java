package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
            "mockgds.providers.beta.latency-min=2s", "mockgds.providers.beta.latency-max=1s")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("beta.latency-min"));
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
  void rejectsANegativeLatency() {
    runner
        .withPropertyValues(KEYS)
        .withPropertyValues("mockgds.providers.alpha.latency-min=-1ms")
        .run(
            context ->
                assertThat(context).getFailure().hasStackTraceContaining("alpha.latency-min"));
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
}
