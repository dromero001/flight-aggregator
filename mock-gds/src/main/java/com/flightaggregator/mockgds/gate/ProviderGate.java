package com.flightaggregator.mockgds.gate;

import com.flightaggregator.mockgds.model.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards one provider's paths: its API key, then its latency, then its error rate. Each refusal is
 * logged at WARN with the provider and the path, never a key or a header value.
 */
class ProviderGate implements HandlerInterceptor {

  static final String API_KEY_HEADER = "X-Api-Key";

  private static final Logger LOG = LoggerFactory.getLogger(ProviderGate.class);

  private final Provider provider;
  private final ProviderSettings.Entry entry;
  private final RandomGenerator random;
  private final Sleeper sleeper;

  ProviderGate(
      Provider provider, ProviderSettings.Entry entry, RandomGenerator random, Sleeper sleeper) {
    this.provider = provider;
    this.entry = entry;
    this.random = random;
    this.sleeper = sleeper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!hasKey(request)) {
      LOG.warn("{} {}: 401, missing or wrong API key", provider, request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }
    sleeper.sleep(latency());
    if (random.nextDouble() < entry.errorRate()) {
      LOG.warn("{} {}: 500, injected failure", provider, request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return false;
    }
    return true;
  }

  /** A time in the slow range for the slow share of requests, and in the fast range otherwise. */
  private Duration latency() {
    return random.nextDouble() < entry.slowShare()
        ? between(entry.slowLatencyMin(), entry.slowLatencyMax())
        : between(entry.fastLatencyMin(), entry.fastLatencyMax());
  }

  private Duration between(Duration min, Duration max) {
    return Duration.ofNanos(random.nextLong(min.toNanos(), max.toNanos() + 1));
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
