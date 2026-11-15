package com.flightaggregator.mockgds.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.support.ProviderKeys;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The gate on its own, with a seeded generator and a sleeper that only records. */
class ProviderGateTest {

  @Test
  void rejectsAWrongKeyBeforeAnyLatencyOrError() throws Exception {
    var sleeper = new RecordingSleeper();
    var gate =
        new ProviderGate(
            Provider.GAMMA,
            constantLatency(ProviderKeys.GAMMA, Duration.ofSeconds(5), 1.0),
            new Random(1),
            sleeper);
    var request = new MockHttpServletRequest("GET", "/gamma/probe");
    request.addHeader(ProviderGate.API_KEY_HEADER, "wrong-key");
    var response = new MockHttpServletResponse();

    var proceeds = gate.preHandle(request, response, new Object());

    assertThat(proceeds).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(sleeper.sleeps()).isEmpty();
    assertThat(response.getContentAsString())
        .doesNotContain(ProviderKeys.ALPHA, ProviderKeys.BETA, ProviderKeys.GAMMA);
  }

  /** One line per refusal, with the provider and the path; never a key or a header value. */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void logsEachRefusalWithItsProviderAndPath(CapturedOutput output) throws Exception {
    var gate =
        new ProviderGate(
            Provider.GAMMA,
            constantLatency(ProviderKeys.GAMMA, Duration.ZERO, 1.0),
            new Random(1),
            new RecordingSleeper());

    gate.preHandle(
        request("/gamma/legs.csv", "wrong-key"), new MockHttpServletResponse(), new Object());
    gate.preHandle(
        request("/gamma/legs.csv", ProviderKeys.GAMMA),
        new MockHttpServletResponse(),
        new Object());

    assertThat(output.getAll().lines().filter(line -> line.contains("ProviderGate")))
        .satisfiesExactly(
            line -> assertThat(line).contains("WARN", "GAMMA", "/gamma/legs.csv", "401"),
            line -> assertThat(line).contains("WARN", "GAMMA", "/gamma/legs.csv", "500"));
    assertThat(output.getAll()).doesNotContain("wrong-key", ProviderKeys.GAMMA);
  }

  @Test
  void waitsBeforeFailingARequest() throws Exception {
    var sleeper = new RecordingSleeper();
    var gate =
        new ProviderGate(
            Provider.ALPHA,
            constantLatency(ProviderKeys.ALPHA, Duration.ofSeconds(2), 1.0),
            new Random(3),
            sleeper);
    var response = new MockHttpServletResponse();

    gate.preHandle(request("/alpha/probe", ProviderKeys.ALPHA), response, new Object());

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
            Provider.ALPHA,
            constantLatency(ProviderKeys.ALPHA, Duration.ZERO, errorRate),
            new Random(11),
            new RecordingSleeper());
    var failures = 0;
    for (var i = 0; i < requests; i++) {
      var response = new MockHttpServletResponse();
      var proceeds =
          gate.preHandle(request("/alpha/probe", ProviderKeys.ALPHA), response, new Object());
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
        ProviderKeys.BETA,
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
    var gate = new ProviderGate(Provider.BETA, entry, new Random(7), sleeper);
    for (var i = 0; i < requests; i++) {
      gate.preHandle(
          request("/beta/probe", ProviderKeys.BETA), new MockHttpServletResponse(), new Object());
    }
    return sleeper.sleeps();
  }

  private static MockHttpServletRequest request(String path, String apiKey) {
    var request = new MockHttpServletRequest("GET", path);
    request.addHeader(ProviderGate.API_KEY_HEADER, apiKey);
    return request;
  }

  /** Every request waits exactly {@code latency}. */
  private static ProviderSettings.Entry constantLatency(
      String apiKey, Duration latency, double errorRate) {
    return new ProviderSettings.Entry(apiKey, latency, latency, latency, latency, 0.0, errorRate);
  }
}
