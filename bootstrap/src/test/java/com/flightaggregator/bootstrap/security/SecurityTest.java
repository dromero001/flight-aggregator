package com.flightaggregator.bootstrap.security;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.bootstrap.PostgresContainers;
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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Spec §6.6: search and re-price are public; the admin side needs a token with the ADMIN role. */
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
class SecurityTest {

  private static final String ISSUER = "http://localhost:8180/realms/flight-aggregator";

  /** Keycloak's key endpoint, served by WireMock; the tokens are signed with its private key. */
  private static final RSAKey KEY = signingKey();

  private static final WireMockServer KEYS = new WireMockServer(options().dynamicPort());

  @DynamicPropertySource
  static void keycloakKeys(DynamicPropertyRegistry registry) {
    KEYS.start();
    KEYS.stubFor(
        WireMock.get("/certs").willReturn(okJson(new JWKSet(KEY.toPublicJWK()).toString())));
    registry.add("security.jwt.jwk-set-uri", () -> KEYS.baseUrl() + "/certs");
    registry.add("security.jwt.issuer", () -> ISSUER);
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

  @Test
  void healthNeedsNoTokenAndShowsNoDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void healthShowsItsDetailsToTheAdmin() throws Exception {
    mvc.perform(
            get("/actuator/health")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.db.status").value("UP"));
  }

  @Test
  void metricsAnswer401WithoutAToken() throws Exception {
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
  }

  @Test
  void metricsAnswer403WithoutTheAdminRole() throws Exception {
    mvc.perform(
            get("/actuator/metrics")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void metricsAnswer200WithTheAdminRealmRole() throws Exception {
    mvc.perform(
            get("/actuator/metrics")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsATokenOfAnotherIssuer() throws Exception {
    String token = signed("http://localhost:8180/realms/another-realm");

    mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  /** A real signed token, through the real decoder and the role converter. */
  @Test
  void acceptsASignedTokenOfTheIssuerWithTheAdminRealmRole() throws Exception {
    String token = signed(ISSUER);

    mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  private static String signed(String issuer) throws JOSEException {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("admin")
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("realm_access", Map.of("roles", List.of("ADMIN")))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(KEY));
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
