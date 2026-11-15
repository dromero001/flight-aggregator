package com.flightaggregator.bootstrap.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The application is an OAuth2 resource server (spec 6.6), its decoder and role mapping configured
 * by Spring Boot's {@code spring.security.oauth2.resourceserver.jwt.*} settings. Search, re-price,
 * the API description and the health status are public; everything else, the admin endpoints and
 * the other Actuator endpoints included, needs the ADMIN realm role. Only bearer tokens are read,
 * so there is no session and no CSRF protection to bypass.
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
                    .requestMatchers(HttpMethod.GET, "/api/itineraries")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/itineraries/*/reprice")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/v3/api-docs")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN"))
        .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
        .build();
  }
}
