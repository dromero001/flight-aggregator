package com.flightaggregator.mockgds.serving;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards one provider's paths: its API key, then its latency, then its error rate. */
class ProviderGate implements HandlerInterceptor {

  static final String API_KEY_HEADER = "X-Api-Key";

  private final ProviderSettings.Entry entry;
  private final RandomGenerator random;
  private final Sleeper sleeper;

  ProviderGate(ProviderSettings.Entry entry, RandomGenerator random, Sleeper sleeper) {
    this.entry = entry;
    this.random = random;
    this.sleeper = sleeper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!hasKey(request)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }
    sleeper.sleep(latency());
    if (random.nextDouble() < entry.errorRate()) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return false;
    }
    return true;
  }

  private Duration latency() {
    return Duration.ofNanos(
        random.nextLong(entry.latencyMin().toNanos(), entry.latencyMax().toNanos() + 1));
  }

  /** Compares in constant time, so the response time reveals nothing about the key. */
  private boolean hasKey(HttpServletRequest request) {
    var presented = request.getHeader(API_KEY_HEADER);
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8),
        entry.apiKey().getBytes(StandardCharsets.UTF_8));
  }
}
