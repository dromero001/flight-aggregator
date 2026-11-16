package com.flightaggregator.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcIngestionLockTest {

  private final DataSource dataSource = PostgresTestDatabase.dataSource();

  /** A lock of each test's own, so the tests share no row. */
  private final String name = "ingestion-" + UUID.randomUUID();

  /** ShedLock's provider as the application configures it: over the database's clock. */
  private final LockProvider shedLock =
      new JdbcTemplateLockProvider(
          JdbcTemplateLockProvider.Configuration.builder()
              .withJdbcTemplate(new JdbcTemplate(dataSource))
              .usingDbTime()
              .build());

  private final JdbcIngestionLock lock =
      new JdbcIngestionLock(new DataSourceTransactionManager(dataSource), name);

  /** As the ingestion job takes it: for at most 29 minutes, released when the run ends. */
  private SimpleLock takeTheLock() {
    return shedLock
        .lock(new LockConfiguration(Instant.now(), name, Duration.ofMinutes(29), Duration.ZERO))
        .orElseThrow();
  }

  @Test
  void isHeldWhileARunHoldsIt() {
    SimpleLock run = takeTheLock();
    try {
      assertThat(lock.isHeld()).isTrue();
    } finally {
      run.unlock();
    }
  }

  /** Another lock in the same table (the refresh's, plan 5) is not the ingestion's. */
  @Test
  void isNotHeldWhileOnlyAnotherLockIsHeld() {
    SimpleLock other =
        shedLock
            .lock(
                new LockConfiguration(
                    Instant.now(),
                    "refresh-" + UUID.randomUUID(),
                    Duration.ofMinutes(29),
                    Duration.ZERO))
            .orElseThrow();
    try {
      assertThat(lock.isHeld()).isFalse();
    } finally {
      other.unlock();
    }
  }

  /** ShedLock keeps the row and sets its end to the release, since the job asks for no minimum. */
  @Test
  void isNotHeldOnceTheRunReleasesIt() {
    takeTheLock().unlock();

    assertThat(lock.isHeld()).isFalse();
  }
}
