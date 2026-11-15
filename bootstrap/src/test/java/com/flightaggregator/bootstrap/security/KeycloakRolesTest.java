package com.flightaggregator.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRolesTest {

  private final KeycloakRoles roles = new KeycloakRoles();

  @Test
  void mapsRealmRolesToAuthorities() {
    Jwt token = token(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "other"))));

    assertThat(roles.convert(token))
        .extracting("authority")
        .containsExactly("ROLE_ADMIN", "ROLE_other");
  }

  @Test
  void givesNoAuthorityWithoutTheClaim() {
    assertThat(roles.convert(token(Map.of("sub", "someone")))).isEmpty();
    assertThat(roles.convert(token(Map.of("realm_access", Map.of())))).isEmpty();
  }

  private static Jwt token(Map<String, Object> claims) {
    return new Jwt(
        "token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }
}
