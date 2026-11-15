package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mockgds.providers.alpha.api-key=test-alpha-key",
      "mockgds.providers.beta.api-key=test-beta-key",
      "mockgds.providers.gamma.api-key=test-gamma-key",
      "mockgds.providers.alpha.error-rate=0",
      "mockgds.providers.beta.error-rate=0",
      "mockgds.providers.gamma.error-rate=0"
    })
@AutoConfigureTestRestTemplate
class ProviderGateTest {

  @Autowired private TestRestTemplate http;

  @Test
  void rejectsAMissingKey() {
    var response = http.getForEntity("/alpha/probe", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsAnotherProvidersKey() {
    var response = get("/alpha/probe", "test-beta-key");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsAWrongKeyBeforeAnyLatencyOrError() throws Exception {
    var sleeper = new RecordingSleeper();
    var gate =
        new ProviderGate(
            settings(Duration.ofSeconds(5), Duration.ofSeconds(5), 1.0), new Random(1), sleeper);
    var request = new MockHttpServletRequest("GET", "/gamma/probe");
    request.addHeader("X-Api-Key", "wrong-key");
    var response = new MockHttpServletResponse();

    var proceeds = gate.preHandle(request, response, new Object());

    assertThat(proceeds).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(sleeper.sleeps()).isEmpty();
    assertThat(response.getContentAsString())
        .doesNotContain("test-alpha-key", "test-beta-key", "test-gamma-key");
  }

  @Test
  void passesAValidKeyThrough() {
    var response = get("/gamma/probe", "test-gamma-key");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("reached");
  }

  @Test
  void leavesActuatorUngated() {
    var response = http.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void waitsALatencyInsideTheConfiguredRange() throws Exception {
    var min = Duration.ofMillis(100);
    var max = Duration.ofMillis(5000);
    var sleeper = new RecordingSleeper();
    var gate = new ProviderGate(settings(min, max, 0.0), new Random(7), sleeper);

    for (var i = 0; i < 1_000; i++) {
      assertThat(
              gate.preHandle(
                  request("/beta/probe", "test-beta-key"),
                  new MockHttpServletResponse(),
                  new Object()))
          .isTrue();
    }

    var sleeps = sleeper.sleeps();
    var tolerance = max.minus(min).dividedBy(20);
    assertThat(sleeps).hasSize(1_000).allSatisfy(d -> assertThat(d).isBetween(min, max));
    assertThat(Collections.min(sleeps)).isLessThanOrEqualTo(min.plus(tolerance));
    assertThat(Collections.max(sleeps)).isGreaterThanOrEqualTo(max.minus(tolerance));
  }

  @Test
  void failsAtTheConfiguredRate() throws Exception {
    assertThat(failuresOutOf(0.0, 1_000)).isZero();
    assertThat(failuresOutOf(1.0, 1_000)).isEqualTo(1_000);
    assertThat(failuresOutOf(0.05, 10_000)).isBetween(400, 600);
  }

  private static int failuresOutOf(double errorRate, int requests) throws Exception {
    var gate =
        new ProviderGate(
            settings(Duration.ZERO, Duration.ZERO, errorRate),
            new Random(11),
            new RecordingSleeper());
    var failures = 0;
    for (var i = 0; i < requests; i++) {
      var response = new MockHttpServletResponse();
      var proceeds =
          gate.preHandle(request("/alpha/probe", "test-alpha-key"), response, new Object());
      if (response.getStatus() == 500) {
        assertThat(proceeds).isFalse();
        failures++;
      }
    }
    return failures;
  }

  private static MockHttpServletRequest request(String path, String apiKey) {
    var request = new MockHttpServletRequest("GET", path);
    request.addHeader("X-Api-Key", apiKey);
    return request;
  }

  private static ProviderSettings settings(Duration min, Duration max, double errorRate) {
    return new ProviderSettings(
        new ProviderSettings.Entry("test-alpha-key", min, max, errorRate),
        new ProviderSettings.Entry("test-beta-key", min, max, errorRate),
        new ProviderSettings.Entry("test-gamma-key", min, max, errorRate));
  }

  private ResponseEntity<String> get(String path, String apiKey) {
    var headers = new HttpHeaders();
    headers.set("X-Api-Key", apiKey);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
