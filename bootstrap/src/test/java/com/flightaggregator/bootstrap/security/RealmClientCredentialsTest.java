package com.flightaggregator.bootstrap.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.bootstrap.PostgresContainers;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * The realm file of the repository, imported by a real Keycloak: the {@code admin-service} client's
 * client-credentials token is accepted by the application as an ADMIN (spec 6.6), the way the
 * README's "Admin access" section gets one.
 */
@SpringBootTest(
    properties = {
      "providers.alpha.base-url=http://localhost:1/",
      "providers.alpha.api-key=alpha-key",
      "providers.beta.base-url=http://localhost:1/",
      "providers.beta.api-key=beta-key",
      "providers.gamma.base-url=http://localhost:1/",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers(PostgresContainers.class)
@AutoConfigureMockMvc
class RealmClientCredentialsTest {

  private static final String REALM = "/realms/flight-aggregator";

  /** The realm file is at the repository's root; the tests run in the module's directory. */
  @SuppressWarnings("resource")
  private static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(KeycloakImage.NAME)
          .withCommand("start-dev", "--import-realm")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../deploy/keycloak/flight-aggregator-realm.json"),
              "/opt/keycloak/data/import/flight-aggregator-realm.json")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp(REALM).forPort(8080));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry registry) throws IOException, InterruptedException {
    KEYCLOAK.start();
    String realm = "http://localhost:" + KEYCLOAK.getMappedPort(8080) + REALM;
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> realm + "/protocol/openid-connect/certs");
    // Without KC_HOSTNAME Keycloak names itself by the address of the request.
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realm);
  }

  @Autowired private MockMvc mvc;

  @Test
  void acceptsTheClientCredentialsTokenOfTheRealmsAdminClient() throws Exception {
    String token = clientCredentialsToken("admin-service", "demo-admin-service-secret");

    mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  private static String clientCredentialsToken(String client, String secret)
      throws IOException, InterruptedException {
    String body =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(client, StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + KEYCLOAK.getMappedPort(8080)
                                + REALM
                                + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Keycloak answered " + response.statusCode());
    }
    return JSON.readTree(response.body()).path("access_token").asString();
  }
}
