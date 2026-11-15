package com.flightaggregator.bootstrap.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Keycloak puts a user's realm roles under {@code realm_access.roles}; each becomes ROLE_name. */
public class KeycloakRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

  @Override
  public Collection<GrantedAuthority> convert(Jwt token) {
    Object realmAccess = token.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> access && access.get("roles") instanceof List<?> roles) {
      return roles.stream()
          .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .toList();
    }
    return List.of();
  }
}
