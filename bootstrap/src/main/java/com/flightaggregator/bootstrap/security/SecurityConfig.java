package com.flightaggregator.bootstrap.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The application is an OAuth2 resource server (spec §6.6). Search, re-price, the API description
 * and the health status are public; everything else, the admin endpoints and the other Actuator
 * endpoints included, needs the ADMIN realm role. Only bearer tokens are read, so there is no
 * session and no CSRF protection to bypass.
 */
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    JwtAuthenticationConverter roles = new JwtAuthenticationConverter();
    roles.setJwtGrantedAuthoritiesConverter(new KeycloakRoles());
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/api/itineraries")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/itineraries/*/reprice")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/v3/api-docs")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN"))
        .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(roles)))
        .build();
  }

  /**
   * The signature is checked against the keys Keycloak serves on the network the application
   * reaches it by ({@code jwk-set-uri}, {@code keycloak} in compose), and the issuer against the
   * name a browser token carries ({@code issuer}, fixed by KC_HOSTNAME): the two differ.
   */
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${security.jwt.jwk-set-uri}") String jwkSetUri,
      @Value("${security.jwt.issuer}") String issuer) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
    return decoder;
  }
}
