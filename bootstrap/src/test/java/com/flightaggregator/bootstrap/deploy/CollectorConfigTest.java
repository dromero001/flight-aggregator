package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/** The Collector compose runs accepts the repository's configuration (spec 6.10). */
class CollectorConfigTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG = "../deploy/otel-collector/config.yaml";

  @Test
  void validatesTheCollectorConfiguration() {
    try (GenericContainer<?> collector =
        new GenericContainer<>(ObservabilityImages.COLLECTOR)
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG), "/etc/otelcol/config.yaml")
            .withCommand("validate", "--config=/etc/otelcol/config.yaml")
            // start() fails unless the command exits with 0.
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy())) {

      assertThatNoException().isThrownBy(collector::start);
    }
  }
}
