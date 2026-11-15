package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The alert rules Prometheus loads are valid and pass their promtool unit tests (plan 7, Q3-A and
 * Q4), run by the {@code promtool} of the image compose runs. The globs are {@code
 * prometheus.yaml}'s: the rules are {@code rules/*.rules.yaml}, and their tests sit beside them as
 * {@code rules/*.rules.test.yaml}. A failure shows promtool's output.
 */
class AlertRulesTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG_DIRECTORY = "../deploy/prometheus";

  @Test
  void passesEveryRuleTest() throws IOException, InterruptedException {
    try (GenericContainer<?> prometheus =
        new GenericContainer<>(ObservabilityImages.PROMETHEUS)
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG_DIRECTORY), "/etc/prometheus")
            // Kept alive for up to ten minutes instead of running Prometheus, so promtool runs
            // inside it and its output can be read.
            .withCreateContainerCmdModifier(command -> command.withEntrypoint("sleep"))
            .withCommand("600")) {
      prometheus.start();

      assertSucceeds(
          prometheus, "promtool check rules --lint-fatal /etc/prometheus/rules/*.rules.yaml");
      assertSucceeds(prometheus, "promtool test rules /etc/prometheus/rules/*.rules.test.yaml");
    }
  }

  /** Runs the command in the image's shell, which expands the glob as Prometheus does. */
  private static void assertSucceeds(GenericContainer<?> prometheus, String command)
      throws IOException, InterruptedException {
    ExecResult result = prometheus.execInContainer("sh", "-c", command);

    assertThat(result.getExitCode())
        .as("%s%n%s%s", command, result.getStdout(), result.getStderr())
        .isZero();
  }
}
