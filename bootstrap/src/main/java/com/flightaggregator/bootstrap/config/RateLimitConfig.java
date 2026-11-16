package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.web.RateLimitInterceptor;
import com.flightaggregator.adapter.web.RateLimitResponses;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.service.RateLimitService;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The rate limit in front of the public endpoints (§6.6): its use case over Valkey's request
 * counters, with the limits of {@code rate-limit.actions}, the interceptor that applies it, and its
 * 429 in the contract.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

  @Bean
  AdmitRequest admitRequest(RequestCounter counter, RateLimitProperties limits) {
    return new RateLimitService(counter, limits.actions());
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
