package com.flightaggregator.bootstrap.config;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The ingestion's lock (§6.2), a ShedLock row in PostgreSQL that the job takes through the {@code
 * LockingTaskExecutor}, so that one instance at a time runs it and the others skip their run; and
 * the job's schedule.
 */
@Configuration(proxyBeanMethods = false)
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

  @Bean
  LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }

  /**
   * Scheduling itself, off unless {@code ingestion.schedule.enabled} is true. The job takes the
   * lock either way, so a test that turns the schedule off and calls the job still takes it.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("ingestion.schedule.enabled")
  static class Schedule {}
}
