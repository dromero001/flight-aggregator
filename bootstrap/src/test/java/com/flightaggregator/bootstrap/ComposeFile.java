package com.flightaggregator.bootstrap;

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

  private ComposeFile() {}
}
