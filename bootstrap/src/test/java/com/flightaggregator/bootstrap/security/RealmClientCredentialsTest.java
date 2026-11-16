package com.flightaggregator.bootstrap.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.bootstrap.AggregatorTest;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The realm file of the repository, imported by a real Keycloak: the {@code admin-service} client's
 * client-credentials token is accepted by the application as an ADMIN (spec 6.6), the way the
 * README's "Admin access" section gets one.
 */
@AggregatorTest(containers = {PostgresContainers.class, KeycloakContainers.class})
@AutoConfigureMockMvc
class RealmClientCredentialsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mvc;

  @Test
  void acceptsTheClientCredentialsTokenOfTheRealmsAdminClient() throws Exception {
    String token = clientCredentialsToken("admin-service", "demo-admin-service-secret");

    mvc.perform(get("/api/admin/status").header("Authorization", "Bearer " + token))
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
                        URI.create(KeycloakContainers.realm() + "/protocol/openid-connect/token"))
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
