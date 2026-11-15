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

/** The Loki compose runs, with the repository's configuration (spec 6.10). */
class LokiConfigTest {

  /** The tests run in the module's directory; deploy/ is at the repository's root. */
  private static final String CONFIG = "../deploy/loki/loki.yaml";

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @SuppressWarnings("resource")
  private static final GenericContainer<?> LOKI =
      new GenericContainer<>(ObservabilityImages.LOKI)
          .withCopyFileToContainer(MountableFile.forHostPath(CONFIG), "/etc/loki/loki.yaml")
          .withCommand("-config.file=/etc/loki/loki.yaml")
          .withExposedPorts(3100)
          .waitingFor(
              Wait.forHttp("/ready").forPort(3100).withStartupTimeout(Duration.ofMinutes(2)));

  @BeforeAll
  static void startLoki() {
    LOKI.start();
  }

  @AfterAll
  static void stopLoki() {
    LOKI.stop();
  }

  @Test
  void startsWithTheRepositorysConfiguration() throws IOException, InterruptedException {
    assertThat(get("/ready").statusCode()).isEqualTo(200);
  }

  /**
   * Loki sends anonymous usage statistics to Grafana Labs unless {@code
   * analytics.reporting_enabled} is false (Loki 3.7.9's documentation, "Usage statistics"); {@code
   * /config} shows the configuration Loki runs with. The user's decision of 2026-11-15.
   */
  @Test
  @SuppressWarnings("unchecked")
  void sendsNoUsageReport() throws IOException, InterruptedException {
    Map<String, Map<String, Object>> running = new Yaml().load(get("/config").body());

    assertThat(running.get("analytics")).containsEntry("reporting_enabled", false);
  }

  private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
    URI uri = URI.create("http://" + LOKI.getHost() + ":" + LOKI.getMappedPort(3100) + path);
    return HTTP.send(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
