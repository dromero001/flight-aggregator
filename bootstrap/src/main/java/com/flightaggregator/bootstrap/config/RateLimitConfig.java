package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.web.RateLimitInterceptor;
import com.flightaggregator.adapter.web.RateLimitResponses;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.service.RateLimitService;
import java.time.Duration;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The rate limit in front of the public endpoints (§6.6): its use case over Valkey's request
 * counters, the interceptor that applies it, and its 429 in the contract.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  /**
   * Q9: a person paging and changing filters stays well under 60 searches a minute; each re-price
   * calls the providers (the look-to-book cost, §6.2), so it has 10 a minute, on its own counter.
   */
  private static final Map<RateLimitedAction, RateLimit> LIMITS =
      Map.of(
          RateLimitedAction.SEARCH, new RateLimit(60, Duration.ofMinutes(1)),
          RateLimitedAction.REPRICE, new RateLimit(10, Duration.ofMinutes(1)));

  @Bean
  AdmitRequest admitRequest(RequestCounter counter) {
    return new RateLimitService(counter, LIMITS);
  }

  /** The 429 of every rate-limited endpoint, documented from its {@code @RateLimited}. */
  @Bean
  OperationCustomizer rateLimitResponses() {
    return new RateLimitResponses();
  }

  @Bean
  WebMvcConfigurer rateLimit(AdmitRequest admitRequest) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(admitRequest));
      }
    };
  }
}
