package com.flightaggregator.bootstrap;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An error Spring MVC itself answers is an RFC 9457 problem, as the API's own errors are (G1, the
 * user's decision of 2026-11-16), so a client reads one error format everywhere. The requests carry
 * an admin token: without one, Spring Security answers every path but the public ones with 401.
 */
@AggregatorTest
@AutoConfigureMockMvc
class FrameworkProblemsTest {

  @Autowired private MockMvc mvc;

  @Test
  void answersAMethodTheEndpointDoesNotTakeWithAProblem() throws Exception {
    mvc.perform(
            delete("/api/admin/status")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(jsonPath("$.instance").value("/api/admin/status"));
  }

  @Test
  void answersAPathTheApiDoesNotServeWithAProblem() throws Exception {
    mvc.perform(
            get("/api/admin/nothing-here")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.instance").value("/api/admin/nothing-here"));
  }
}
