package com.flightaggregator.bootstrap.security;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.bootstrap.AggregatorTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Spec §6.6: search and re-price are public; the admin side needs a token with the ADMIN role. */
@AggregatorTest
@AutoConfigureMockMvc
class SecurityTest {

  /** The issuer application.yaml ships, read from there: no test setting stands in for it. */
  private static final String ISSUER = "http://localhost:8180/realms/flight-aggregator";

  /** Keycloak's key endpoint, served by WireMock; the tokens are signed with its private key. */
  private static final RSAKey KEY = signingKey();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final WireMockServer KEYS = new WireMockServer(options().dynamicPort());

  @DynamicPropertySource
  static void keycloakKeys(DynamicPropertyRegistry registry) {
    KEYS.start();
    KEYS.stubFor(
        WireMock.get("/certs").willReturn(okJson(new JWKSet(KEY.toPublicJWK()).toString())));
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> KEYS.baseUrl() + "/certs");
  }

  @AfterAll
  static void stopKeys() {
    KEYS.stop();
  }

  @Autowired private MockMvc mvc;

  /** The endpoints arrive with Tasks 9 and 10: here only that no token is asked for. */
  @Test
  void searchAndRepriceNeedNoToken() throws Exception {
    MvcResult search = mvc.perform(get("/api/itineraries")).andReturn();
    MvcResult reprice = mvc.perform(post("/api/itineraries/some-id/reprice")).andReturn();

    assertThat(search.getResponse().getStatus()).isNotIn(401, 403);
    assertThat(reprice.getResponse().getStatus()).isNotIn(401, 403);
  }

  /**
   * §6.6: the search is public, and a HEAD is that GET without its body (RFC 9110, 9.3.2), which
   * Spring MVC answers from the same endpoint and the rate limit counts as a search.
   */
  @Test
  void searchByHeadNeedsNoToken() throws Exception {
    mvc.perform(head("/api/itineraries")).andExpect(status().isOk());
  }

  @Test
  void rejectsATokenOfAnotherIssuer() throws Exception {
    String token = signed("http://localhost:8180/realms/another-realm", 300, KEY, "ADMIN");

    mvc.perform(get("/api/admin/status").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  /** Beyond the decoder's 60 seconds of clock skew. */
  @Test
  void rejectsAnExpiredToken() throws Exception {
    String token = signed(ISSUER, -300, KEY, "ADMIN");

    mvc.perform(get("/api/admin/status").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  /** Another key under the same key id: only the signature tells it from Keycloak's token. */
  @Test
  void rejectsATokenSignedWithAnotherKey() throws Exception {
    String token = signed(ISSUER, 300, signingKey(), "ADMIN");

    mvc.perform(get("/api/admin/status").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void answers403ToAValidTokenWithoutTheAdminRealmRole() throws Exception {
    String token = signed(ISSUER, 300, KEY, "user");

    mvc.perform(get("/api/admin/status").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  /** A real signed token, through the real decoder and the role mapping. */
  @Test
  void acceptsASignedTokenOfTheIssuerWithTheAdminRealmRole() throws Exception {
    String token = signed(ISSUER, 300, KEY, "ADMIN");

    mvc.perform(get("/api/admin/status").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  /** §6.6: the admin endpoints answer 401 without a token and 403 without the ADMIN role. */
  @ParameterizedTest
  @CsvSource({"GET, /api/admin/status", "POST, /api/admin/ingestions"})
  void adminEndpointsNeedTheAdminRole(String method, String path) throws Exception {
    mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isUnauthorized());
    mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
        .andExpect(status().isForbidden());
  }

  /**
   * The contract and the filter chain say the same (plan 5 Task 13's C1): an anonymous request to
   * each operation the served contract documents answers 401 exactly where that operation names the
   * bearer scheme, so neither can change without the other.
   */
  @Test
  void asksForATokenExactlyWhereTheContractNamesTheBearerScheme() throws Exception {
    JsonNode paths =
        JSON.readTree(
                mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("paths");
    Map<String, Boolean> namesTheBearer = new TreeMap<>();
    Map<String, Boolean> answers401 = new TreeMap<>();
    for (String path : paths.propertyNames()) {
      for (String method : paths.path(path).propertyNames()) {
        String operation = method.toUpperCase(Locale.ROOT) + " " + path;
        namesTheBearer.put(
            operation,
            paths
                .path(path)
                .path(method)
                .path("security")
                .valueStream()
                .anyMatch(requirement -> requirement.has("bearer")));
        int status =
            mvc.perform(
                    request(
                        HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)),
                        path.replace("{id}", UUID.randomUUID().toString())))
                .andReturn()
                .getResponse()
                .getStatus();
        answers401.put(operation, status == 401);
      }
    }

    assertThat(namesTheBearer).containsValues(true, false);
    assertThat(answers401).isEqualTo(namesTheBearer);
  }

  /** The admin's status reaches the endpoint, wired in the application. */
  @Test
  void adminStatusAnswers200WithTheAdminRealmRole() throws Exception {
    mvc.perform(
            get("/api/admin/status")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generation").isNumber());
  }

  private static String signed(String issuer, long secondsToExpiry, RSAKey key, String role)
      throws JOSEException {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("admin")
            .expirationTime(Date.from(Instant.now().plusSeconds(secondsToExpiry)))
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static RSAKey signingKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("test-key").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
