package com.flightaggregator.bootstrap.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The ingestion's lock (§6.2): a ShedLock row in PostgreSQL, so that one instance at a time runs
 * the job, whatever calls it, and the others skip their run. ShedLock requires a default for {@code
 * lockAtMostFor}; the job's own {@code @SchedulerLock} sets the same one.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT29M")
class SchedulingConfig {

  /**
   * Over Boot's {@code JdbcTemplate}, with the database's clock, so that the instances' clocks
   * cannot disagree about when a lock ends.
   */
  @Bean
  LockProvider lockProvider(JdbcTemplate jdbc) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbc)
            .usingDbTime()
            .build());
  }

  /**
   * Scheduling itself, off unless {@code ingestion.schedule.enabled} is true. The lock above holds
   * either way, so a test that turns the schedule off and calls the job still takes the lock.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("ingestion.schedule.enabled")
  static class Schedule {}
}
