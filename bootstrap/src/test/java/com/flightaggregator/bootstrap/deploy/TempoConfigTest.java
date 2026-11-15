package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/** The Tempo compose runs, with the repository's configuration (spec 6.10). */
class TempoConfigTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG = "../deploy/tempo/tempo.yaml";

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @SuppressWarnings("resource")
  private static final GenericContainer<?> TEMPO =
      new GenericContainer<>(ObservabilityImages.TEMPO)
          .withCopyFileToContainer(MountableFile.forHostPath(CONFIG), "/etc/tempo/tempo.yaml")
          .withCommand("-config.file=/etc/tempo/tempo.yaml")
          .withExposedPorts(3200)
          .waitingFor(
              Wait.forHttp("/ready").forPort(3200).withStartupTimeout(Duration.ofMinutes(2)));

  @BeforeAll
  static void startTempo() {
    TEMPO.start();
  }

  @AfterAll
  static void stopTempo() {
    TEMPO.stop();
  }

  @Test
  void startsWithTheRepositorysConfiguration() throws IOException, InterruptedException {
    assertThat(get("/ready").statusCode()).isEqualTo(200);
  }

  /**
   * Tempo reports anonymous usage data to Grafana Labs unless {@code
   * usage_report.reporting_enabled} is false (Tempo 3.1.1's configuration reference,
   * "Usage-report"); {@code /status/config} shows the configuration Tempo runs with. The user's
   * decision of 2026-11-15.
   */
  @Test
  void sendsNoUsageReport() throws IOException, InterruptedException {
    assertThat(runningConfiguration().get("usage_report"))
        .containsEntry("reporting_enabled", false);
  }

  /** {@code /status/config} answers its request line, then the configuration as a YAML document. */
  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> runningConfiguration()
      throws IOException, InterruptedException {
    Object configuration = null;
    for (Object document : new Yaml().loadAll(get("/status/config").body())) {
      configuration = document;
    }
    return (Map<String, Map<String, Object>>) configuration;
  }

  private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
    URI uri = URI.create("http://" + TEMPO.getHost() + ":" + TEMPO.getMappedPort(3200) + path);
    return HTTP.send(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
