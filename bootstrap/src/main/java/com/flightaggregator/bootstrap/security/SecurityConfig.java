package com.flightaggregator.bootstrap.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * The application is an OAuth2 resource server (spec 6.6), its decoder and role mapping configured
 * by Spring Boot's {@code spring.security.oauth2.resourceserver.jwt.*} settings. Search, re-price,
 * the API description, Swagger UI and the health status ({@code /health}) are public, and so is
 * everything on Actuator's management port, which only compose's network reaches; everything else,
 * the admin endpoints included, needs the ADMIN realm role. Only bearer tokens are read, so there
 * is no session and no CSRF protection to bypass.
 */
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    // The request was authorized already; its forward to /error must not be asked
                    // again, or an error of a public path would reach an anonymous client as 401.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // Everything Actuator serves on its management port, which only compose's
                    // network reaches: Prometheus scrapes it with no token (spec 6.6).
                    .requestMatchers(SecurityConfig::servedByTheManagementServer)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/itineraries")
                    .permitAll()
                    // A HEAD is the search without its body (RFC 9110), answered by the same
                    // endpoint and counted by the same rate limit.
                    .requestMatchers(HttpMethod.HEAD, "/api/itineraries")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/itineraries/*/reprice")
                    .permitAll()
                    // The health status on the API's own port, the additional path of
                    // application.yaml's health group (plan 7, the user's decision of 2026-11-16).
                    .requestMatchers(
                        EndpointRequest.toAdditionalPaths(
                            WebServerNamespace.SERVER, HealthEndpoint.class))
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/v3/api-docs")
                    .permitAll()
                    // Swagger UI (plan 5's extra E2): its page, its assets and the configuration it
                    // reads first. The operations it tries call the API's own paths, so its admin
                    // operations still need a token.
                    .requestMatchers(
                        HttpMethod.GET,
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/swagger-config")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN"))
        .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
        .build();
  }

  /**
   * Whether the request reached Actuator's own server, by the namespace of the context serving it,
   * not by a port number: a request to the API's server never matches, even if both shared a port.
   * Boot's {@code EndpointRequest.toAnyEndpoint()} is not used for this: once both share a port (no
   * {@code management.server.port}), it matches the API's server too, and would open every Actuator
   * endpoint there to anyone.
   */
  private static boolean servedByTheManagementServer(HttpServletRequest request) {
    return WebServerNamespace.MANAGEMENT.equals(
        WebServerNamespace.from(
            WebApplicationContextUtils.getWebApplicationContext(request.getServletContext())));
  }
}
