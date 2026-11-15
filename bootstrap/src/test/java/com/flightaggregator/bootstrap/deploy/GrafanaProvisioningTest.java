package com.flightaggregator.bootstrap.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.bootstrap.ComposeFile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Grafana compose runs, with compose's settings: the team opens it without an account, as
 * anonymous Viewers (spec 6.6, the user's decision of 2026-11-15).
 */
class GrafanaProvisioningTest {

  /**
   * Mounted by compose as Grafana's provisioning directory. The tests run in the module's
   * directory; deploy/ is at the repository's root.
   */
  private static final String PROVISIONING = "../deploy/grafana/provisioning";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** Lives only in this test's container; compose sets none (ComposeFileTest). */
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

  @SuppressWarnings("resource")
  private static final GenericContainer<?> GRAFANA =
      new GenericContainer<>(ObservabilityImages.GRAFANA)
          .withCopyFileToContainer(
              MountableFile.forHostPath(PROVISIONING), "/etc/grafana/provisioning")
          .withExposedPorts(3000)
          .waitingFor(Wait.forHttp("/api/health").forPort(3000));

  @BeforeAll
  static void startGrafanaWithComposesSettings() throws IOException {
    GRAFANA
        .withEnv(composeEnvironment("grafana"))
        .withEnv("GF_SECURITY_ADMIN_PASSWORD", ADMIN_PASSWORD)
        .start();
  }

  @AfterAll
  static void stopGrafana() {
    GRAFANA.stop();
  }

  /**
   * {@code /api/user} answers 401 to an anonymous visitor; {@code /bootdata}, which Grafana's own
   * pages read, says who the visitor is and with which role.
   */
  @Test
  void opensToAnAnonymousViewer() throws IOException, InterruptedException {
    HttpResponse<String> bootData = send(HttpRequest.newBuilder(uri("/bootdata")).GET());
    HttpResponse<String> save =
        send(
            HttpRequest.newBuilder(uri("/api/dashboards/db"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"dashboard\":{\"title\":\"Saved by an anonymous Viewer\"}}")));

    assertThat(JSON.readTree(bootData.body()).path("user").path("orgRole").asString())
        .isEqualTo("Viewer");
    assertThat(save.statusCode()).isEqualTo(403);
  }

  /**
   * The jump from a log line to its trace happens in Explore, which Grafana closes to Viewers
   * unless {@code viewers_can_edit} is on (plan 7, Q1-A). The setting is deprecated: this fails on
   * the Grafana that stops honouring it.
   */
  @Test
  void opensExploreToTheAnonymousViewer() throws IOException, InterruptedException {
    HttpResponse<String> permissions =
        send(HttpRequest.newBuilder(uri("/api/access-control/user/permissions")).GET());

    assertThat(JSON.readTree(permissions.body()).propertyNames()).contains("datasources:explore");
  }

  /**
   * Explore's query history was the one thing an anonymous Viewer could save under Q1-A, shared by
   * every visitor; it is off (the user's decision of 2026-11-15, after the task's review).
   */
  @Test
  void savesNoQueryHistory() throws IOException, InterruptedException {
    HttpResponse<String> save =
        send(
            HttpRequest.newBuilder(uri("/api/query-history"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"datasourceUid\":\"loki\",\"queries\":[{\"refId\":\"A\",\"expr\":\"{}\"}]}")));

    assertThat(save.statusCode()).isBetween(400, 499);
  }

  /**
   * With the login form and basic authentication off, nobody signs in, not even with the right
   * password (plan 7, "Decisions this plan takes"): the test gives its own container's built-in
   * administrator a password, and both ways of signing in refuse it. A wrong password would be
   * refused with sign-in on as well.
   */
  @Test
  void letsNoOneSignIn() throws IOException, InterruptedException {
    String credentials =
        Base64.getEncoder()
            .encodeToString(("admin:" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> basic =
        send(
            HttpRequest.newBuilder(uri("/api/user"))
                .header("Authorization", "Basic " + credentials)
                .GET());
    HttpResponse<String> form =
        send(
            HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(
                            Map.of("user", "admin", "password", ADMIN_PASSWORD)))));

    assertThat(basic.statusCode()).isEqualTo(401);
    assertThat(form.statusCode()).isBetween(400, 499);
  }

  /**
   * Read as the anonymous Viewer, as the team reads them: Grafana's open-source edition lets a
   * Viewer list the datasources, so the test needs no administrator. A provisioned datasource is
   * read-only unless its file says {@code editable: true}.
   */
  @Test
  void provisionsTheThreeDatasources() throws IOException, InterruptedException {
    JsonNode datasources =
        JSON.readTree(send(HttpRequest.newBuilder(uri("/api/datasources")).GET()).body());

    assertThat(datasources.valueStream().map(datasource -> datasource.path("uid").asString()))
        .containsExactlyInAnyOrder("prometheus", "tempo", "loki");
    assertThat(datasources.valueStream().map(datasource -> datasource.path("readOnly").asBoolean()))
        .containsOnly(true);
  }

  /**
   * Grafana sends anonymous usage statistics to {@code stats.grafana.org} unless {@code [analytics]
   * reporting_enabled} is false (Grafana 13.3's "Configure Grafana", section {@code analytics});
   * the user's decision of 2026-11-15. Grafana shows the setting only to an administrator, and
   * nobody can sign in as one, so this checks compose's override of it, {@code GF_<SECTION>_<KEY>}
   * as that page documents.
   */
  @Test
  void sendsNoUsageReport() throws IOException {
    assertThat(composeEnvironment("grafana"))
        .containsEntry("GF_ANALYTICS_REPORTING_ENABLED", "false");
  }

  /**
   * Nor does it ask grafana.com for new versions of itself or its plugins ({@code [analytics]
   * check_for_updates} and {@code check_for_plugin_updates}, same page); the user's decision of
   * 2026-11-15, after the task's review. Checked in compose for the same reason.
   */
  @Test
  void checksForNoUpdates() throws IOException {
    assertThat(composeEnvironment("grafana"))
        .containsEntry("GF_ANALYTICS_CHECK_FOR_UPDATES", "false")
        .containsEntry("GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES", "false");
  }

  private static URI uri(String path) {
    return URI.create("http://" + GRAFANA.getHost() + ":" + GRAFANA.getMappedPort(3000) + path);
  }

  private static HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> composeEnvironment(String service) throws IOException {
    Map<String, Object> definition = ComposeFile.services().get(service);
    assertThat(definition).as("service %s in compose.yaml", service).isNotNull();
    Map<String, Object> environment = (Map<String, Object>) definition.get("environment");
    assertThat(environment).as("environment of %s in compose.yaml", service).isNotNull();
    Map<String, String> asStrings = new LinkedHashMap<>();
    environment.forEach((name, value) -> asStrings.put(name, String.valueOf(value)));
    return asStrings;
  }
}
