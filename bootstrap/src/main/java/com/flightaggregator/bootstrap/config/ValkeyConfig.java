package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.persistence.ValkeyFailures;
import com.flightaggregator.adapter.persistence.ValkeyRefreshCoordination;
import com.flightaggregator.adapter.persistence.ValkeyRequestCounter;
import com.flightaggregator.adapter.persistence.ValkeySearchCache;
import com.flightaggregator.application.port.out.RefreshCoordination;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.SearchCache;
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
   * Q9: while Lettuce has lost its connection, a command fails at once instead of queueing until it
   * reconnects. Boot builds the rest of the client options, the connect timeout among them, from
   * {@code spring.data.redis.*}, and applies this after.
   */
  @Bean
  LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
    return options -> options.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
  }

  @Bean
  ValkeyFailures valkeyFailures() {
    return new ValkeyFailures();
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
