package com.flightaggregator.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.application.port.in.ProviderStatus;
import com.flightaggregator.application.port.in.ProviderStatus.Health;
import com.flightaggregator.application.port.in.StatusReport;
import com.flightaggregator.application.port.in.TriggerIngestion.TriggerOutcome;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.ProviderBreakers.BreakerState;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminController.class)
@Import(AdminControllerTest.Admin.class)
class AdminControllerTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Admin {

    @Bean
    RecordingAdmin admin() {
      return new RecordingAdmin();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private RecordingAdmin admin;

  @BeforeEach
  void startAfresh() {
    admin.reset();
  }

  /**
   * The provider-status screen (§6.8): the generation, when it was published and when the next run
   * is due, and each provider's row; a time not known yet is written as null.
   */
  @Test
  void servesTheStatus() throws Exception {
    admin.answer(
        new StatusReport(
            7,
            Optional.of(Instant.parse("2026-11-20T08:01:00Z")),
            Optional.empty(),
            false,
            List.of(
                new ProviderStatus(
                    Provider.ALPHA,
                    Health.UP,
                    BreakerState.CLOSED,
                    Optional.of(Instant.parse("2026-11-20T08:00:40Z")),
                    120,
                    Map.of(DiscardReason.PRICE, 2),
                    false),
                new ProviderStatus(
                    Provider.BETA,
                    Health.DOWN,
                    BreakerState.OPEN,
                    Optional.empty(),
                    0,
                    Map.of(),
                    true))));

    mvc.perform(get("/api/admin/status"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {
                      "generation": 7,
                      "publishedAt": "2026-11-20T08:01:00Z",
                      "nextIngestionAt": null,
                      "providers": [
                        {
                          "provider": "ALPHA",
                          "health": "UP",
                          "breaker": "CLOSED",
                          "newestQuotedAt": "2026-11-20T08:00:40Z",
                          "legs": 120,
                          "discarded": {"PRICE": 2},
                          "stale": false
                        },
                        {
                          "provider": "BETA",
                          "health": "DOWN",
                          "breaker": "OPEN",
                          "newestQuotedAt": null,
                          "legs": 0,
                          "discarded": {},
                          "stale": true
                        }
                      ]
                    }
                    """,
                    JsonCompareMode.STRICT));
  }

  /** "Run ingestion now" (§6.8): the run goes on in the background, so the request is accepted. */
  @Test
  void startsAnIngestion() throws Exception {
    mvc.perform(post("/api/admin/ingestions"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));

    assertThat(admin.triggers()).isOne();
  }

  /** A run holds the ingestion's lock, scheduled or on demand: a conflict, as a problem. */
  @Test
  void refusesASecondRunWhileOneHoldsTheLock() throws Exception {
    admin.answer(TriggerOutcome.ALREADY_RUNNING);

    mvc.perform(post("/api/admin/ingestions"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.detail").value("An ingestion is already running."))
        .andExpect(jsonPath("$.instance").value("/api/admin/ingestions"));
  }
}
