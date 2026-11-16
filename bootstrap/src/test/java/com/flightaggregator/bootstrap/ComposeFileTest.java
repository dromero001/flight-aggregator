package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresImage;
import com.flightaggregator.adapter.persistence.support.ValkeyImage;
import com.flightaggregator.bootstrap.deploy.ObservabilityImages;
import com.flightaggregator.bootstrap.security.KeycloakImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code docker compose up} runs the images the tests run, each pinned by tag and digest (spec
 * §6.1), so that an update of either one fails here until the other follows; and it exposes only
 * what the decisions recorded below allow.
 */
class ComposeFileTest {

  /**
   * The tests run in the module's directory; compose's build contexts are at the repository's root.
   */
  private static final Path REPOSITORY = Path.of("..");

  /** The Keycloak realm compose mounts for import. */
  private static final Path REALM =
      REPOSITORY.resolve("deploy/keycloak/flight-aggregator-realm.json");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** An exact tag and the digest of the image's multi-arch index (spec 6.1). */
  private static final Pattern PINNED = Pattern.compile("[^\\s@]+:[^\\s@:/]+@sha256:[0-9a-f]{64}");

  /** A Dockerfile's {@code FROM} instruction; any other line, a continuation too, is not one. */
  private static final Pattern FROM = Pattern.compile("\\s*FROM\\s", Pattern.CASE_INSENSITIVE);

  /** A BuildKit cache mount, with its options. */
  private static final Pattern CACHE_MOUNT = Pattern.compile("--mount=type=cache\\S*");

  @Test
  void runsThePostgresImageTheTestsRun() throws IOException {
    assertThat(ComposeFile.services().get("postgres").get("image")).isEqualTo(PostgresImage.NAME);
  }

  @Test
  void runsTheValkeyImageTheTestsRun() throws IOException {
    assertThat(ComposeFile.services().get("valkey").get("image")).isEqualTo(ValkeyImage.NAME);
  }

  /**
   * Valkey is a cache (spec 6.2): its memory is bounded, and the least recently used key, of any
   * kind, makes room for a new one, as plan 5 Task 13 sets it.
   */
  @Test
  void boundsValkeysMemoryAndEvictsTheLeastRecentlyUsedKey() throws IOException {
    assertThat(ComposeFile.services().get("valkey").get("command"))
        .isEqualTo(
            List.of("valkey-server", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"));
  }

  /**
   * Valkey answers a ping before it counts as healthy (plan 5 Task 13): the app waits for that, and
   * compose refuses to start a service that waits for a dependency with no health check.
   */
  @Test
  @SuppressWarnings("unchecked")
  void callsValkeyHealthyOnceItAnswersAPing() throws IOException {
    Map<String, Object> healthcheck =
        (Map<String, Object>) ComposeFile.services().get("valkey").get("healthcheck");

    assertThat(healthcheck)
        .containsEntry("test", List.of("CMD-SHELL", "valkey-cli ping | grep -q PONG"));
  }

  /**
   * The app reaches Valkey by its service name, and starts once the stores and the token issuer it
   * calls answer (plan 5 Task 13).
   */
  @Test
  @SuppressWarnings("unchecked")
  void startsTheAppOnValkeyOnceValkeyAndKeycloakAreHealthy() throws IOException {
    Map<String, Object> app = ComposeFile.services().get("app");

    assertThat(ComposeFile.environment("app")).containsEntry("SPRING_DATA_REDIS_HOST", "valkey");
    assertThat((Map<String, Object>) app.get("depends_on"))
        .containsEntry("valkey", Map.of("condition", "service_healthy"))
        .containsEntry("keycloak", Map.of("condition", "service_healthy"));
  }

  @Test
  void runsTheKeycloakImageTheTestsRun() throws IOException {
    assertThat(ComposeFile.services().get("keycloak").get("image")).isEqualTo(KeycloakImage.NAME);
  }

  @Test
  void runsTheObservabilityImagesTheTestsRun() throws IOException {
    Map<String, Object> images = new HashMap<>();
    ComposeFile.services().forEach((name, service) -> images.put(name, service.get("image")));

    assertThat(images)
        .containsAllEntriesOf(
            Map.of(
                "otel-collector", ObservabilityImages.COLLECTOR,
                "prometheus", ObservabilityImages.PROMETHEUS,
                "tempo", ObservabilityImages.TEMPO,
                "loki", ObservabilityImages.LOKI,
                "grafana", ObservabilityImages.GRAFANA));
  }

  /**
   * Keycloak starts with no administrator console account, under either generation of its
   * variables: an admin token comes from a realm client, not from a console login (the user's
   * decision of 2026-11-15).
   */
  @Test
  void givesKeycloakNoAdministratorAccount() throws IOException {
    assertThat(ComposeFile.environment("keycloak").keySet())
        .noneMatch(
            name -> name.startsWith("KC_BOOTSTRAP_ADMIN") || name.startsWith("KEYCLOAK_ADMIN"));
  }

  /**
   * The password of the realm's {@code admin} user is a demo default that the shell or a {@code
   * .env} file overrides, as the client secret is (spec 6.6): compose hands Keycloak the variable,
   * and the realm file reads it at import.
   */
  @Test
  void letsTheShellOverrideTheDemoPasswordOfTheRealmsAdmin() throws IOException {
    JsonNode admin =
        StreamSupport.stream(JSON.readTree(REALM.toFile()).path("users").spliterator(), false)
            .filter(user -> user.path("username").asString().equals("admin"))
            .findFirst()
            .orElseThrow();

    assertThat(ComposeFile.environment("keycloak"))
        .containsEntry("ADMIN_USER_PASSWORD", "${ADMIN_USER_PASSWORD:-demo-admin-password}");
    assertThat(admin.path("credentials").path(0).path("value").asString())
        .isEqualTo("${ADMIN_USER_PASSWORD:demo-admin-password}");
  }

  /**
   * No account or password is committed for Grafana (plan 7, Global Constraints): no {@code
   * [security] admin_*} setting, nor its {@code __FILE} form, so the built-in administrator keeps
   * Grafana's default and cannot sign in (sign-in is off, {@code GrafanaProvisioningTest}).
   */
  @Test
  void givesGrafanaNoAdministratorAccount() throws IOException {
    assertThat(ComposeFile.environment("grafana").keySet())
        .noneMatch(name -> name.startsWith("GF_SECURITY_ADMIN_"));
  }

  /**
   * Neither store is published: only the app reaches PostgreSQL and Valkey, on compose's network,
   * since anyone who reached Valkey could reset any client's rate-limit counter (plan 5 Task 13).
   * The app, the dashboard, Keycloak and the mock providers are published on the loopback interface
   * only (README, "Run the aggregator").
   */
  @Test
  void publishesNeitherStoreAndTheRestOnTheLoopbackOnly() throws IOException {
    Map<String, Object> ports = new HashMap<>();
    ComposeFile.services().forEach((name, service) -> ports.put(name, service.get("ports")));

    assertThat(ports)
        .containsEntry("postgres", null)
        .containsEntry("valkey", null)
        .containsEntry("app", List.of("127.0.0.1:8080:8080"))
        .containsEntry("frontend", List.of("127.0.0.1:3000:8080"))
        .containsEntry("keycloak", List.of("127.0.0.1:8180:8080"))
        .containsEntry("mock-gds", List.of("127.0.0.1:8081:8080"));
  }

  /**
   * Of the telemetry services, only Grafana and Prometheus's alerts page are published, on the
   * loopback interface (plan 7, Q4); the Collector, Tempo and Loki stay on compose's network.
   */
  @Test
  void publishesOnlyGrafanaAndPrometheusOfTheTelemetryOnTheLoopback() throws IOException {
    Map<String, Object> ports = new HashMap<>();
    ComposeFile.services().forEach((name, service) -> ports.put(name, service.get("ports")));

    assertThat(ports)
        .containsEntry("grafana", List.of("127.0.0.1:3001:3000"))
        .containsEntry("prometheus", List.of("127.0.0.1:9090:9090"))
        .containsEntry("otel-collector", null)
        .containsEntry("tempo", null)
        .containsEntry("loki", null);
  }

  /**
   * Actuator's management port, open with no token, is reached only on compose's network, where
   * Prometheus scrapes it (spec 6.6; plan 7, Review Focus 3): the app never publishes it, in the
   * short syntax ({@code [host:]published:container}) or the long one ({@code target}).
   */
  @Test
  @SuppressWarnings("unchecked")
  void neverPublishesTheManagementPort() throws IOException {
    Map<String, Map<String, Object>> application =
        new Yaml().load(Files.readString(Path.of("src/main/resources/application.yaml")));
    String managementPort =
        String.valueOf(
            ((Map<String, Object>) application.get("management").get("server")).get("port"));

    List<Object> published =
        (List<Object>) ComposeFile.services().get("app").getOrDefault("ports", List.of());

    assertThat(published)
        .extracting(ComposeFileTest::containerPort)
        .isNotEmpty()
        .doesNotContain(managementPort);
  }

  private static String containerPort(Object port) {
    if (port instanceof Map<?, ?> longSyntax) {
      return String.valueOf(longSyntax.get("target"));
    }
    String shortSyntax = String.valueOf(port);
    return shortSyntax.substring(shortSyntax.lastIndexOf(':') + 1).replace("/tcp", "");
  }

  /**
   * Every image compose runs, pulled or built, is pinned (spec 6.1; plan 7, E8). A built service's
   * images are the {@code FROM} lines of its Dockerfile, except those naming an earlier stage.
   */
  @Test
  void pinsEveryImageByTagAndDigest() throws IOException {
    for (Map.Entry<String, Map<String, Object>> service : ComposeFile.services().entrySet()) {
      assertThat(imagesOf(service.getValue()))
          .as("images of %s", service.getKey())
          .isNotEmpty()
          .allSatisfy(
              image -> assertThat(image).as("image of %s", service.getKey()).matches(PINNED));
    }
  }

  /**
   * {@code docker compose up --build} builds the images at once, and their Gradle cache mounts are
   * one cache: unless each mount is locked, two Gradle runs share its journal and one times out
   * waiting for the other's lock (CI's first end-to-end run, 2026-11-16).
   */
  @Test
  void locksEveryGradleCacheTheImageBuildsShare() throws IOException {
    List<String> mounts = new ArrayList<>();
    for (Map<String, Object> service : ComposeFile.services().values()) {
      for (Path dockerfile : dockerfileOf(service).stream().toList()) {
        for (String line : Files.readAllLines(dockerfile)) {
          Matcher mount = CACHE_MOUNT.matcher(line);
          while (mount.find()) {
            mounts.add(dockerfile + ": " + mount.group());
          }
        }
      }
    }
    assertThat(mounts)
        .isNotEmpty()
        .allSatisfy(mount -> assertThat(mount).contains("sharing=locked"));
  }

  private static List<String> imagesOf(Map<String, Object> service) throws IOException {
    Optional<Path> dockerfile = dockerfileOf(service);
    if (dockerfile.isEmpty()) {
      return Stream.ofNullable((String) service.get("image")).toList();
    }
    return baseImages(dockerfile.get());
  }

  /** The Dockerfile compose builds the service from, if it builds one. */
  @SuppressWarnings("unchecked")
  private static Optional<Path> dockerfileOf(Map<String, Object> service) {
    Object build = service.get("build");
    if (build == null) {
      return Optional.empty();
    }
    if (build instanceof String context) {
      return Optional.of(REPOSITORY.resolve(context).resolve("Dockerfile"));
    }
    Map<String, Object> definition = (Map<String, Object>) build;
    return Optional.of(
        REPOSITORY
            .resolve((String) definition.getOrDefault("context", "."))
            .resolve((String) definition.getOrDefault("dockerfile", "Dockerfile")));
  }

  /** {@code FROM [--flag...] image [AS stage]}: the images, without the names of earlier stages. */
  private static List<String> baseImages(Path dockerfile) throws IOException {
    List<String> images = new ArrayList<>();
    Set<String> stages = new HashSet<>();
    for (String line : Files.readAllLines(dockerfile)) {
      if (!FROM.matcher(line).lookingAt()) {
        continue;
      }
      List<String> words =
          Arrays.stream(line.trim().split("\\s+")).filter(word -> !word.startsWith("--")).toList();
      if (!stages.contains(words.get(1).toLowerCase(Locale.ROOT))) {
        images.add(words.get(1));
      }
      if (words.size() == 4 && words.get(2).equalsIgnoreCase("AS")) {
        stages.add(words.get(3).toLowerCase(Locale.ROOT));
      }
    }
    return images;
  }
}
