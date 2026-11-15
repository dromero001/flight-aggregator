package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** The Tempo compose runs starts with the repository's configuration (spec 6.10). */
class TempoConfigTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG = "../deploy/tempo/tempo.yaml";

  @Test
  void startsWithTheRepositorysConfiguration() {
    try (GenericContainer<?> tempo =
        new GenericContainer<>(ObservabilityImages.TEMPO)
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG), "/etc/tempo/tempo.yaml")
            .withCommand("-config.file=/etc/tempo/tempo.yaml")
            .withExposedPorts(3200)
            // start() fails unless /ready answers 200 in time.
            .waitingFor(
                Wait.forHttp("/ready").forPort(3200).withStartupTimeout(Duration.ofMinutes(2)))) {

      assertThatNoException().isThrownBy(tempo::start);
    }
  }
}
