package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** The services of {@code compose.yaml}, as {@code docker compose up} reads them. */
public final class ComposeFile {

  /** The tests run in the module's directory; compose.yaml is at the repository's root. */
  private static final Path COMPOSE = Path.of("..", "compose.yaml");

  /** Each service's definition, by the service's name. */
  public static Map<String, Map<String, Object>> services() throws IOException {
    Map<String, Map<String, Map<String, Object>>> compose =
        new Yaml().load(Files.readString(COMPOSE));
    return compose.get("services");
  }

  /**
   * A service's environment, by variable name. Compose also takes a list of {@code NAME=value}
   * lines; this file writes a map, and a test reading a list fails here, saying so.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> environment(String service) throws IOException {
    Object environment = services().get(service).get("environment");
    assertThat(environment)
        .as("the environment of %s in compose.yaml, written as a map", service)
        .isInstanceOf(Map.class);
    return (Map<String, Object>) environment;
  }

  private ComposeFile() {}
}
