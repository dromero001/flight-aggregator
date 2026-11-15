package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.persistence.ValkeyFailures;
import com.flightaggregator.adapter.persistence.ValkeyRefreshCoordination;
import com.flightaggregator.adapter.persistence.ValkeyRequestCounter;
import com.flightaggregator.adapter.persistence.ValkeySearchCache;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.SearchCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.CircuitBreakerMetricNames;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Valkey stores (§6.2), over the {@code StringRedisTemplate} Boot builds on Lettuce from {@code
 * spring.data.redis.*}. They share one {@link ValkeyFailures}, so a Valkey outage is logged once,
 * not once per store.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class ValkeyConfig {

  /**
   * The meters Resilience4j's binding gives a breaker, named {@code valkey.circuitbreaker.*}
   * instead of the providers' breakers' {@code resilience4j.circuitbreaker.*}, so that a dashboard
   * tells Valkey's breaker from theirs.
   */
  private static final CircuitBreakerMetricNames VALKEY_BREAKER_METERS =
      CircuitBreakerMetricNames.custom()
          .stateMetricName("valkey.circuitbreaker.state")
          .callsMetricName("valkey.circuitbreaker.calls")
          .notPermittedCallsMetricName("valkey.circuitbreaker.not.permitted.calls")
          .bufferedCallsMetricName("valkey.circuitbreaker.buffered.calls")
          .slowCallsMetricName("valkey.circuitbreaker.slow.calls")
          .failureRateMetricName("valkey.circuitbreaker.failure.rate")
          .slowCallRateMetricName("valkey.circuitbreaker.slow.call.rate")
          .build();

  /**
   * Q9: while Lettuce has lost its connection, a command fails at once instead of queueing until it
   * reconnects. Boot builds the rest of the client options, the connect timeout among them, from
   * {@code spring.data.redis.*}, and applies this after.
   */
  @Bean
  LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
    return options -> options.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
  }

  /**
   * Valkey's circuit breaker: after a failed command the stores skip Valkey for {@code
   * search.valkey-skip}, then one call probes it. It is not in the providers' registry, whose
   * breakers are named after providers and whose transitions are logged as theirs, but in one of
   * its own, so that Resilience4j's binding makes its state a meter as it does theirs.
   */
  @Bean
  CircuitBreaker valkeyCircuitBreaker(SearchProperties search, MeterRegistry meters) {
    CircuitBreakerRegistry valkeyBreakers =
        CircuitBreakerRegistry.of(ValkeyFailures.circuitBreakerConfig(search.valkeySkip()));
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(VALKEY_BREAKER_METERS, valkeyBreakers)
        .bindTo(meters);
    return valkeyBreakers.circuitBreaker("valkey");
  }

  @Bean
  ValkeyFailures valkeyFailures(CircuitBreaker valkeyCircuitBreaker) {
    return new ValkeyFailures(valkeyCircuitBreaker);
  }

  @Bean
  SearchCache searchCache(
      StringRedisTemplate valkey,
      JsonMapper json,
      SearchProperties search,
      ValkeyFailures failures,
      MeterRegistry meters) {
    return new ValkeySearchCache(
        valkey, json.tokenStreamFactory(), search.cacheTtl(), failures, meters);
  }

  @Bean
  RefreshCoordination refreshCoordination(
      StringRedisTemplate valkey, SearchProperties search, ValkeyFailures failures) {
    return new ValkeyRefreshCoordination(
        valkey, search.refreshClaimTtl(), search.freshnessThreshold(), failures);
  }

  @Bean
  RequestCounter requestCounter(StringRedisTemplate valkey, ValkeyFailures failures) {
    return new ValkeyRequestCounter(valkey, failures);
  }
}
