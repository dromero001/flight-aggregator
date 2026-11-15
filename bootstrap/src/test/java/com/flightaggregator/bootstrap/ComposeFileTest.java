package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresImage;
import com.flightaggregator.bootstrap.security.KeycloakImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code docker compose up} runs the PostgreSQL the tests run (spec §6.1): one image, pinned by tag
 * and digest, so that an update of either one fails here until the other follows.
 */
class ComposeFileTest {

  /** The tests run in the module's directory; compose.yaml is at the repository's root. */
  private static final Path COMPOSE = Path.of("..", "compose.yaml");

  @Test
  void runsThePostgresImageTheTestsRun() throws IOException {
    Map<String, Map<String, Map<String, Object>>> compose =
        new Yaml().load(Files.readString(COMPOSE));

    assertThat(compose.get("services").get("postgres").get("image")).isEqualTo(PostgresImage.NAME);
  }

  @Test
  void runsTheKeycloakImageTheTestsRun() throws IOException {
    Map<String, Map<String, Map<String, Object>>> compose =
        new Yaml().load(Files.readString(COMPOSE));

    assertThat(compose.get("services").get("keycloak").get("image")).isEqualTo(KeycloakImage.NAME);
  }

  /**
   * The user's decision of 2026-11-15: the admin token comes from a client, not a console login.
   */
  @Test
  @SuppressWarnings("unchecked")
  void givesKeycloakNoAdministratorAccount() throws IOException {
    Map<String, Map<String, Map<String, Object>>> compose =
        new Yaml().load(Files.readString(COMPOSE));

    Map<String, Object> environment =
        (Map<String, Object>) compose.get("services").get("keycloak").get("environment");

    assertThat(environment.keySet())
        .noneMatch(
            name -> name.startsWith("KC_BOOTSTRAP_ADMIN") || name.startsWith("KEYCLOAK_ADMIN"));
  }
}
