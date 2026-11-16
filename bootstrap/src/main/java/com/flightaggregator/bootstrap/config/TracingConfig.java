package com.flightaggregator.bootstrap.config;

import com.flightaggregator.bootstrap.observability.ContextThreads;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tracing (§6.10): one search, re-price or ingestion run is one trace, so the threads that fan out
 * its provider calls carry the trace context of the code that started them.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

  @Bean
  ThreadFactory contextThreads() {
    return ContextThreads.virtual(ContextSnapshotFactory.builder().build());
  }
}
