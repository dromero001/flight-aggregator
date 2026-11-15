package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.model.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards each provider's path: its API key, then its latency, then its error rate. */
@Component
class ProviderGate implements HandlerInterceptor {

  static final String API_KEY_HEADER = "X-Api-Key";

  private final ProviderSettings settings;

  private final RandomGenerator random;
  private final Sleeper sleeper;

  ProviderGate(ProviderSettings settings, RandomGenerator random, Sleeper sleeper) {
    this.settings = settings;
    this.random = random;
    this.sleeper = sleeper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    var provider = providerOf(request);
    if (!hasKeyOf(request, provider)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }
    sleeper.sleep(latencyFor(provider));
    if (random.nextDouble() < settings.forProvider(provider).errorRate()) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return false;
    }
    return true;
  }

  private Duration latencyFor(Provider provider) {
    var entry = settings.forProvider(provider);
    return Duration.ofNanos(
        random.nextLong(entry.latencyMin().toNanos(), entry.latencyMax().toNanos() + 1));
  }

  private static Provider providerOf(HttpServletRequest request) {
    var firstSegment = request.getRequestURI().split("/")[1];
    return Provider.valueOf(firstSegment.toUpperCase(Locale.ROOT));
  }

  /** Compares in constant time, so the response time reveals nothing about the key. */
  private boolean hasKeyOf(HttpServletRequest request, Provider provider) {
    var presented = request.getHeader(API_KEY_HEADER);
    if (presented == null) {
      return false;
    }
    var expected = settings.forProvider(provider).apiKey();
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }
}
