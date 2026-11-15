package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/**
 * The Prometheus compose runs accepts the repository's configuration (spec 6.10), checked by the
 * {@code promtool} of the same image. The whole directory is copied, as compose mounts it.
 */
class PrometheusConfigTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG_DIRECTORY = "../deploy/prometheus";

  @Test
  void checksThePrometheusConfiguration() {
    try (GenericContainer<?> promtool =
        new GenericContainer<>(ObservabilityImages.PROMETHEUS)
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG_DIRECTORY), "/etc/prometheus")
            .withCreateContainerCmdModifier(command -> command.withEntrypoint("promtool"))
            .withCommand("check", "config", "/etc/prometheus/prometheus.yaml")
            // start() fails unless the command exits with 0.
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy())) {

      assertThatNoException().isThrownBy(promtool::start);
    }
  }
}
