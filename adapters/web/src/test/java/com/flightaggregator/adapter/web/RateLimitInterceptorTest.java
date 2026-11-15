package com.flightaggregator.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.adapter.web.RecordingAdmission.Asked;
import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.RateLimitedAction;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest({SearchController.class, RepriceController.class})
@Import(RateLimitInterceptorTest.Limited.class)
class RateLimitInterceptorTest {

  /** Both endpoints over test doubles, behind the interceptor as the application registers it. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Limited {

    @Bean
    RecordingSearch search() {
      return new RecordingSearch();
    }

    @Bean
    RecordingReprice reprice() {
      return new RecordingReprice();
    }

    @Bean
    RecordingAdmission admission() {
      return new RecordingAdmission();
    }

    @Bean
    WebMvcConfigurer rateLimit(RecordingAdmission admission) {
      return new WebMvcConfigurer() {
        @Override
        public void addInterceptors(InterceptorRegistry registry) {
          registry.addInterceptor(new RateLimitInterceptor(admission));
        }
      };
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private RecordingSearch search;
  @Autowired private RecordingReprice reprice;
  @Autowired private RecordingAdmission admission;

  @BeforeEach
  void startAfresh() {
    search.reset();
    reprice.reset();
    admission.reset();
  }

  static Stream<Arguments> limitedRequests() {
    return Stream.of(
        argumentSet("search", get("/api/itineraries"), RateLimitedAction.SEARCH),
        argumentSet("search by HEAD", head("/api/itineraries"), RateLimitedAction.SEARCH),
        argumentSet(
            "re-price",
            post("/api/itineraries/{id}/reprice", Cursors.ID),
            RateLimitedAction.REPRICE));
  }

  /**
   * §6.6 and Q9: a client past its limit gets a 429 problem whose {@code Retry-After} is the
   * window's time left in whole seconds, rounded up so that a retry never comes early; the request
   * is counted under the client's address and its own action, and goes no further.
   */
  @ParameterizedTest
  @MethodSource("limitedRequests")
  void answers429WithRetryAfter(RequestBuilder request, RateLimitedAction action) throws Exception {
    admission.answer(new Admission.Rejected(Duration.ofMillis(41_200)));

    mvc.perform(request)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.detail").value(startsWith("Too many requests")));

    assertThat(admission.asked()).containsExactly(new Asked("127.0.0.1", action));
    assertThat(search.queries()).isEmpty();
    assertThat(reprice.ids()).isEmpty();
  }
}
