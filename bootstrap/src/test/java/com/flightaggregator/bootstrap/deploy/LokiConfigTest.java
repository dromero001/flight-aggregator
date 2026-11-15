package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** The Loki compose runs starts with the repository's configuration (spec 6.10). */
class LokiConfigTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG = "../deploy/loki/loki.yaml";

  @Test
  void startsWithTheRepositorysConfiguration() {
    try (GenericContainer<?> loki =
        new GenericContainer<>(ObservabilityImages.LOKI)
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG), "/etc/loki/loki.yaml")
            .withCommand("-config.file=/etc/loki/loki.yaml")
            .withExposedPorts(3100)
            // start() fails unless /ready answers 200 in time.
            .waitingFor(
                Wait.forHttp("/ready").forPort(3100).withStartupTimeout(Duration.ofMinutes(2)))) {

      assertThatNoException().isThrownBy(loki::start);
    }
  }
}
