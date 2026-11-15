package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.flightaggregator.bootstrap.ComposeFile;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
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
    try (GenericContainer<?> promtool = promtoolCheckConfig()) {

      assertThatNoException().isThrownBy(promtool::start);
    }
  }

  /**
   * Promtool's unit tests for the rules (Task 3) sit beside them in {@code rules/}: Prometheus
   * loads every rule file there, and none of the tests, which it cannot parse as rules. The example
   * pair is the test's own, so the check does not depend on the repository's rule files: a glob
   * that matches nothing leaves out the example's line, and one that takes the test fails promtool.
   */
  @Test
  void loadsTheRuleFilesButNotTheirPromtoolTests() {
    try (GenericContainer<?> promtool =
        promtoolCheckConfig()
            .withCopyToContainer(
                Transferable.of("groups: []\n"), "/etc/prometheus/rules/example.rules.yaml")
            .withCopyToContainer(
                Transferable.of(
                    """
                    rule_files:
                      - example.rules.yaml
                    evaluation_interval: 1m
                    tests: []
                    """),
                "/etc/prometheus/rules/example.rules.test.yaml")) {

      assertThatNoException().isThrownBy(promtool::start);
      assertThat(promtool.getLogs()).contains("Checking /etc/prometheus/rules/example.rules.yaml");
    }
  }

  /**
   * With compose's command and tmpfs, Prometheus writes its data and Docker creates no anonymous
   * volume for the {@code /prometheus} its image declares (plan 7, Q4: no volumes; the user's
   * decision of 2026-11-15 accepts that a restart loses the history).
   */
  @Test
  @SuppressWarnings("unchecked")
  void storesItsDataOnComposesTmpfsWithNoVolume() throws IOException {
    Map<String, Object> service = ComposeFile.services().get("prometheus");
    Map<String, String> tmpfs = new LinkedHashMap<>();
    for (Object entry : (List<Object>) service.getOrDefault("tmpfs", List.of())) {
      String[] pathAndOptions = String.valueOf(entry).split(":", 2);
      tmpfs.put(pathAndOptions[0], pathAndOptions.length > 1 ? pathAndOptions[1] : "");
    }
    try (GenericContainer<?> prometheus =
        new GenericContainer<>(ObservabilityImages.PROMETHEUS)
            .withCopyFileToContainer(MountableFile.forHostPath(CONFIG_DIRECTORY), "/etc/prometheus")
            .withCommand(((List<String>) service.get("command")).toArray(String[]::new))
            .withTmpFs(tmpfs)
            .withExposedPorts(9090)
            .waitingFor(
                Wait.forHttp("/-/ready")
                    .forPort(9090)
                    .withStartupTimeout(Duration.ofSeconds(30)))) {

      assertThatNoException().isThrownBy(prometheus::start);
      assertThat(prometheus.getContainerInfo().getMounts())
          .noneMatch(mount -> "/prometheus".equals(mount.getDestination().getPath()));
    }
  }

  /** The image's {@code promtool check config}, on the directory compose mounts. */
  @SuppressWarnings("resource")
  private static GenericContainer<?> promtoolCheckConfig() {
    return new GenericContainer<>(ObservabilityImages.PROMETHEUS)
        .withCopyFileToContainer(MountableFile.forHostPath(CONFIG_DIRECTORY), "/etc/prometheus")
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("promtool"))
        .withCommand("check", "config", "/etc/prometheus/prometheus.yaml")
        // start() fails unless the command exits with 0.
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy());
  }
}
