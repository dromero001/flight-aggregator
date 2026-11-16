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
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /** Mounted by compose where the dashboards' provider reads them. */
  private static final String DASHBOARDS = "../deploy/grafana/dashboards";

  /** The dashboard, by the fixed uid its file sets. */
  private static final String DASHBOARD = "/api/dashboards/uid/flight-aggregator";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** Lives only in this test's container; compose sets none (ComposeFileTest). */
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();

  @SuppressWarnings("resource")
  private static final GenericContainer<?> GRAFANA =
      new GenericContainer<>(ObservabilityImages.GRAFANA)
          .withCopyFileToContainer(
              MountableFile.forHostPath(PROVISIONING), "/etc/grafana/provisioning")
          .withCopyFileToContainer(MountableFile.forHostPath(DASHBOARDS), "/etc/grafana/dashboards")
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
   * The jump from a log line to its trace (spec 6.10's approved extra): the Loki datasource has a
   * derived field that reads each line's {@code trace_id} structured metadata and opens that trace
   * in the Tempo datasource.
   */
  @Test
  void linksALogLineToItsTrace() throws IOException, InterruptedException {
    assertThat(datasource("loki").path("jsonData").path("derivedFields").valueStream())
        .anySatisfy(
            field -> {
              assertThat(field.path("matcherType").asString()).isEqualTo("label");
              assertThat(field.path("matcherRegex").asString()).isEqualTo("trace_id");
              assertThat(field.path("datasourceUid").asString()).isEqualTo("tempo");
              // The trace's query is the matched value itself.
              assertThat(field.path("url").asString()).isEqualTo("${__value.raw}");
            });
  }

  /**
   * The reverse jump, from a span to the lines of its trace (plan 7 extra E1, approved by the user
   * on 2026-11-15 and chosen for the delivery on 2026-11-16): the Tempo datasource opens Loki with
   * a query on the service's stream and the trace's {@code trace_id} structured metadata, since the
   * line itself does not hold the id.
   */
  @Test
  void linksATraceToItsLogLines() throws IOException, InterruptedException {
    JsonNode tracesToLogs = datasource("tempo").at("/jsonData/tracesToLogsV2");

    assertThat(tracesToLogs.path("datasourceUid").asString()).isEqualTo("loki");
    assertThat(tracesToLogs.path("tags").valueStream())
        .anySatisfy(
            tag -> {
              assertThat(tag.path("key").asString()).isEqualTo("service.name");
              assertThat(tag.path("value").asString()).isEqualTo("service_name");
            });
    assertThat(tracesToLogs.path("customQuery").asBoolean()).isTrue();
    assertThat(tracesToLogs.path("query").asString())
        .isEqualTo("{${__tags}} | trace_id=\"${__trace.traceId}\"");
  }

  /**
   * One dashboard with every metric the spec promises (spec 6.10; plan 7 Task 6), then the approved
   * extras' panels (plan 7 E4), read as the anonymous Viewer reads it.
   */
  @Test
  void loadsTheDashboardWithItsPanels() throws IOException, InterruptedException {
    JsonNode dashboard = dashboard();

    assertThat(dashboard.path("title").asString()).isEqualTo("Flight aggregator");
    assertThat(dashboard.path("panels").valueStream().map(panel -> panel.path("title").asString()))
        .containsExactly(
            "Provider calls",
            "Circuit breakers",
            "Data freshness",
            "Ingestion",
            "Cache hit ratio",
            "Discarded records",
            "Partitions",
            "Alerts",
            "Logs");
  }

  /**
   * Each Prometheus panel's expressions name only metrics Prometheus stores, so a misspelt or
   * renamed meter leaves no panel silently empty (spec 6.10; plan 7 Task 6). The logs panel has its
   * own test.
   */
  @Test
  void everyPanelQueriesAKnownMetric() throws IOException, InterruptedException {
    List<JsonNode> metricPanels =
        dashboard()
            .path("panels")
            .valueStream()
            .filter(panel -> panel.at("/datasource/uid").asString().equals("prometheus"))
            .toList();
    assertThat(metricPanels).as("panels on Prometheus").isNotEmpty();
    for (JsonNode panel : metricPanels) {
      String title = panel.path("title").asString();
      assertThat(panel.path("targets").valueStream()).as("queries of %s", title).isNotEmpty();
      for (JsonNode target : panel.path("targets")) {
        String expression = target.path("expr").asString();
        Set<String> names = StoredMetrics.namedIn(expression);
        assertThat(names).as("metrics of %s: %s", title, expression).isNotEmpty();
        assertThat(names.stream().filter(name -> !StoredMetrics.NAMES.contains(name)))
            .as("metrics of %s that Prometheus does not store: %s", title, expression)
            .isEmpty();
      }
    }
  }

  /**
   * The application's log lines one click from the dashboard (plan 7 extra E4): a logs panel on
   * Loki's one stream of the application, where a line's details still link to its trace.
   */
  @Test
  void showsTheApplicationsLogLines() throws IOException, InterruptedException {
    JsonNode logs = panel("Logs");

    assertThat(logs.path("type").asString()).isEqualTo("logs");
    assertThat(logs.at("/datasource/uid").asString()).isEqualTo("loki");
    assertThat(logs.path("targets").valueStream().map(target -> target.path("expr").asString()))
        .containsExactly("{service_name=\"flight-aggregator\"}");
  }

  /**
   * The alerts one click from the dashboard (plan 7 extra E4): Grafana's alert list, which reads
   * Prometheus's rules, shows every rule in every state, so a healthy stack lists its rules as
   * normal instead of an empty panel, as Prometheus's alerts page does. A rule that is not firing
   * has no alert instance, and the list hides such rules unless told to show them.
   */
  @Test
  void listsTheAlertRulesInEveryState() throws IOException, InterruptedException {
    JsonNode alerts = panel("Alerts");

    assertThat(alerts.path("type").asString()).isEqualTo("alertlist");
    JsonNode states = alerts.at("/options/stateFilter");
    assertThat(List.of("firing", "pending", "normal", "error", "noData"))
        .allSatisfy(state -> assertThat(states.path(state).asBoolean()).as(state).isTrue());
    assertThat(alerts.at("/options/showInactiveAlerts").asBoolean())
        .as("rules with no instance shown")
        .isTrue();
  }

  /**
   * A gauge's tile reads "unknown" when no instance reports a number for it, as its alert counts it
   * (plan 7 Task 3: NaN or missing is stale), never its last healthy value in green. The query is
   * instant, so a gauge that went missing leaves no older value to show; NaN, and no series at all,
   * map to "unknown" in the colour of the alert that fires on it: ProviderDataStale's and
   * PartitionHorizonShort's red, and orange for the rows in DEFAULT, whose unknown is
   * PartitionHorizonShort's.
   */
  @Test
  void showsAnUnknownGaugeAsUnknownNeverAsItsLastValue() throws IOException, InterruptedException {
    List<GaugeTile> tiles =
        List.of(
            new GaugeTile("Data freshness", "A", "red"),
            new GaugeTile("Partitions", "A", "red"),
            new GaugeTile("Partitions", "B", "orange"));

    for (GaugeTile tile : tiles) {
      JsonNode panel = panel(tile.panel());
      JsonNode target =
          panel
              .path("targets")
              .valueStream()
              .filter(query -> query.path("refId").asString().equals(tile.refId()))
              .findFirst()
              .orElseThrow();
      assertThat(target.path("instant").asBoolean()).as("%s is instant", tile).isTrue();
      assertThat(target.path("range").asBoolean(true)).as("%s is a range", tile).isFalse();
      assertThat(panel.at("/fieldConfig/defaults/noValue").asString(""))
          .as("%s with no series", tile)
          .isEqualTo("unknown");
      assertThat(nullAndNaN(mappings(panel, tile.refId())))
          .as("%s reading NaN or no series", tile)
          .isEqualTo(Map.of("text", "unknown", "color", tile.unknownColour()));
    }
  }

  /** A stat tile that shows a gauge: its panel, its query and its colour when unknown. */
  private record GaugeTile(String panel, String refId, String unknownColour) {}

  /** The value mappings of a panel's query: an override of its refId's, or the panel's own. */
  private static JsonNode mappings(JsonNode panel, String refId) {
    return panel
        .at("/fieldConfig/overrides")
        .valueStream()
        .filter(override -> override.at("/matcher/id").asString("").equals("byFrameRefID"))
        .filter(override -> override.at("/matcher/options").asString("").equals(refId))
        .flatMap(override -> override.path("properties").valueStream())
        .filter(property -> property.path("id").asString("").equals("mappings"))
        .map(property -> property.path("value"))
        .findFirst()
        .orElse(panel.at("/fieldConfig/defaults/mappings"));
  }

  /** What a mapping list shows for NaN and for no value: its text and colour, empty if neither. */
  private static Map<String, String> nullAndNaN(JsonNode mappings) {
    return mappings
        .valueStream()
        .filter(mapping -> mapping.path("type").asString("").equals("special"))
        .filter(mapping -> mapping.at("/options/match").asString("").equals("null+nan"))
        .map(
            mapping ->
                Map.of(
                    "text", mapping.at("/options/result/text").asString(""),
                    "color", mapping.at("/options/result/color").asString("")))
        .findFirst()
        .orElse(Map.of());
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

  /** A provisioned datasource by its uid, as the anonymous Viewer lists it. */
  private static JsonNode datasource(String uid) throws IOException, InterruptedException {
    return JSON.readTree(send(HttpRequest.newBuilder(uri("/api/datasources")).GET()).body())
        .valueStream()
        .filter(datasource -> datasource.path("uid").asString().equals(uid))
        .findFirst()
        .orElseThrow();
  }

  /** A panel of the dashboard by its title; a missing one fails naming it. */
  private static JsonNode panel(String title) throws IOException, InterruptedException {
    return dashboard()
        .path("panels")
        .valueStream()
        .filter(panel -> panel.path("title").asString().equals(title))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No panel titled " + title));
  }

  private static JsonNode dashboard() throws IOException, InterruptedException {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri(DASHBOARD)).GET());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body()).path("dashboard");
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
