package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
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
      "mockgds.providers.alpha.fast-latency-min=0",
      "mockgds.providers.alpha.fast-latency-max=0",
      "mockgds.providers.alpha.slow-latency-min=0",
      "mockgds.providers.alpha.slow-latency-max=0",
      "mockgds.providers.beta.fast-latency-min=0",
      "mockgds.providers.beta.fast-latency-max=0",
      "mockgds.providers.beta.slow-latency-min=0",
      "mockgds.providers.beta.slow-latency-max=0",
      "mockgds.providers.gamma.fast-latency-min=0",
      "mockgds.providers.gamma.fast-latency-max=0",
      "mockgds.providers.gamma.slow-latency-min=0",
      "mockgds.providers.gamma.slow-latency-max=0",
      "mockgds.providers.alpha.error-rate=0",
      "mockgds.providers.beta.error-rate=0",
      "mockgds.providers.gamma.error-rate=0"
    })
@AutoConfigureTestRestTemplate
class ProviderGateTest {

  @Autowired private TestRestTemplate http;
  @Autowired private RandomGenerator random;

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
            constantLatency("test-gamma-key", Duration.ofSeconds(5), 1.0), new Random(1), sleeper);
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
  void leavesPathsOutsideTheProviderPrefixesUngated() {
    assertThat(http.getForEntity("/probe", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void rejectsAWrongKeyOnAPathWithMatrixParameters() {
    var response = get("/alpha;x=1/probe", "test-beta-key");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void drawsFromAThreadSafeGenerator() {
    // java.util.Random is thread-safe; the default L32X64MixRandom is not, and requests are
    // concurrent on virtual threads.
    assertThat(random).isInstanceOf(Random.class);
  }

  @Test
  void waitsBeforeFailingARequest() throws Exception {
    var sleeper = new RecordingSleeper();
    var gate =
        new ProviderGate(
            constantLatency("test-alpha-key", Duration.ofSeconds(2), 1.0), new Random(3), sleeper);
    var response = new MockHttpServletResponse();

    gate.preHandle(request("/alpha/probe", "test-alpha-key"), response, new Object());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(sleeper.sleeps()).containsExactly(Duration.ofSeconds(2));
  }

  @Test
  void waitsALatencyInsideTheWholeRange() throws Exception {
    var min = Duration.ofMillis(100);
    var max = Duration.ofMillis(5000);

    var latencies = latencies(withSlowShare(0.1), 10_000);

    var tolerance = max.minus(min).dividedBy(20);
    assertThat(latencies).hasSize(10_000).allSatisfy(d -> assertThat(d).isBetween(min, max));
    assertThat(Collections.min(latencies)).isLessThanOrEqualTo(min.plus(tolerance));
    assertThat(Collections.max(latencies)).isGreaterThanOrEqualTo(max.minus(tolerance));
  }

  @Test
  void waitsOnlyAFastLatencyWhenTheSlowShareIsZero() throws Exception {
    assertThat(latencies(withSlowShare(0.0), 10_000))
        .allSatisfy(latency -> assertThat(latency).isLessThanOrEqualTo(Duration.ofMillis(1000)));
  }

  @Test
  void waitsASlowLatencyForTheSlowShareOfRequests() throws Exception {
    var aboveOneSecond =
        latencies(withSlowShare(0.1), 10_000).stream()
            .filter(latency -> latency.compareTo(Duration.ofMillis(1000)) > 0)
            .count();

    assertThat(aboveOneSecond).as("draws above 1000 ms of 10,000").isBetween(900L, 1100L);
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
            constantLatency("test-alpha-key", Duration.ZERO, errorRate),
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

  /** The default latency model: 100–1000 ms, and 1000–5000 ms for the slow share of requests. */
  private static ProviderSettings.Entry withSlowShare(double slowShare) {
    return new ProviderSettings.Entry(
        "test-beta-key",
        Duration.ofMillis(100),
        Duration.ofMillis(1000),
        Duration.ofMillis(1000),
        Duration.ofMillis(5000),
        slowShare,
        0.0);
  }

  /** The latency of each of {@code requests} requests through one seeded gate. */
  private static List<Duration> latencies(ProviderSettings.Entry entry, int requests)
      throws Exception {
    var sleeper = new RecordingSleeper();
    var gate = new ProviderGate(entry, new Random(7), sleeper);
    for (var i = 0; i < requests; i++) {
      gate.preHandle(
          request("/beta/probe", "test-beta-key"), new MockHttpServletResponse(), new Object());
    }
    return sleeper.sleeps();
  }

  private static MockHttpServletRequest request(String path, String apiKey) {
    var request = new MockHttpServletRequest("GET", path);
    request.addHeader("X-Api-Key", apiKey);
    return request;
  }

  /** Every request waits exactly {@code latency}. */
  private static ProviderSettings.Entry constantLatency(
      String apiKey, Duration latency, double errorRate) {
    return new ProviderSettings.Entry(apiKey, latency, latency, latency, latency, 0.0, errorRate);
  }

  private ResponseEntity<String> get(String path, String apiKey) {
    var headers = new HttpHeaders();
    headers.set("X-Api-Key", apiKey);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
