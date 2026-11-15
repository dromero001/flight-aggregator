package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresImage;
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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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

  /** An exact tag and the digest of the image's multi-arch index (spec 6.1). */
  private static final Pattern PINNED = Pattern.compile("[^\\s@]+:[^\\s@:/]+@sha256:[0-9a-f]{64}");

  /** A Dockerfile's {@code FROM} instruction; any other line, a continuation too, is not one. */
  private static final Pattern FROM = Pattern.compile("\\s*FROM\\s", Pattern.CASE_INSENSITIVE);

  @Test
  void runsThePostgresImageTheTestsRun() throws IOException {
    assertThat(ComposeFile.services().get("postgres").get("image")).isEqualTo(PostgresImage.NAME);
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
   * The user's decision of 2026-11-15: the admin token comes from a client, not a console login.
   */
  @Test
  @SuppressWarnings("unchecked")
  void givesKeycloakNoAdministratorAccount() throws IOException {
    Map<String, Object> environment =
        (Map<String, Object>) ComposeFile.services().get("keycloak").get("environment");

    assertThat(environment.keySet())
        .noneMatch(
            name -> name.startsWith("KC_BOOTSTRAP_ADMIN") || name.startsWith("KEYCLOAK_ADMIN"));
  }

  /**
   * No account or password is committed for Grafana (plan 7, Global Constraints): no {@code
   * [security] admin_*} setting, nor its {@code __FILE} form, so the built-in administrator keeps
   * Grafana's default and cannot sign in (sign-in is off, {@code GrafanaProvisioningTest}).
   */
  @Test
  @SuppressWarnings("unchecked")
  void givesGrafanaNoAdministratorAccount() throws IOException {
    Map<String, Object> environment =
        (Map<String, Object>) ComposeFile.services().get("grafana").get("environment");

    assertThat(environment.keySet()).noneMatch(name -> name.startsWith("GF_SECURITY_ADMIN_"));
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

  @SuppressWarnings("unchecked")
  private static List<String> imagesOf(Map<String, Object> service) throws IOException {
    Object build = service.get("build");
    if (build == null) {
      return Stream.ofNullable((String) service.get("image")).toList();
    }
    if (build instanceof String context) {
      return baseImages(REPOSITORY.resolve(context).resolve("Dockerfile"));
    }
    Map<String, Object> definition = (Map<String, Object>) build;
    return baseImages(
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
