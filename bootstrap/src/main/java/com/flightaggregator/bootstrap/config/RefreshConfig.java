package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.out.RefreshScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provisional (the user's decision of 2026-11-15, plan 5 Task 6): the search use case needs a
 * refresh scheduler before Task 7 builds the background refresh, so that the application starts and
 * Tasks 7 and 9 can run in parallel. This one only warns of each origin it is asked to refresh.
 * Task 7 replaces it: a second scheduler beside it stops startup, so it cannot stay by accident.
 */
@Configuration(proxyBeanMethods = false)
class RefreshConfig {

  private static final Logger LOG = LoggerFactory.getLogger(RefreshConfig.class);

  @Bean
  RefreshScheduler refreshScheduler() {
    return origin ->
        LOG.atWarn()
            .addKeyValue("origin", origin.code())
            .log("A searched origin is stale, but its background refresh is not built yet");
  }
}
