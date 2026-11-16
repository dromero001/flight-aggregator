package com.flightaggregator.bootstrap.security;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A real Keycloak that imports the repository's realm file, as compose's does, imported with
 * {@code @ImportTestcontainers}, which starts it and runs {@link #keycloak} (see {@code
 * PostgresContainers}): the application then checks tokens against its keys and its issuer.
 */
public interface KeycloakContainers {

  /** The realm's path on the server. */
  String REALM = "/realms/flight-aggregator";

  /** The realm file is at the repository's root; the tests run in the module's directory. */
  GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(KeycloakImage.NAME)
          .withCommand("start-dev", "--import-realm")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../deploy/keycloak/flight-aggregator-realm.json"),
              "/opt/keycloak/data/import/flight-aggregator-realm.json")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp(REALM).forPort(8080));

  /** The realm's address from the host, once the container has started. */
  static String realm() {
    return "http://localhost:" + KEYCLOAK.getMappedPort(8080) + REALM;
  }

  /** Without {@code KC_HOSTNAME}, Keycloak names itself by the address of the request. */
  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> realm() + "/protocol/openid-connect/certs");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakContainers::realm);
  }
}
