package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.IngestionReport;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.flightaggregator.bootstrap.ingestion.IngestionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** The job's schedule, read from what Spring registered, so no test waits for a clock. */
class SchedulingConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SchedulingConfig.Schedule.class)
          .withBean(
              IngestionJob.class,
              () ->
                  new IngestionJob(
                      SchedulingConfigTest::nothingToPublish,
                      new IngestionMetrics(new SimpleMeterRegistry(), Instant::now),
                      new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> {})),
                      Instant::now))
          .withPropertyValues("ingestion.interval=PT30M");

  @Test
  void schedulesTheJobFromStartupEveryInterval() {
    runner
        .withPropertyValues("ingestion.schedule.enabled=true")
        .run(
            context ->
                assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                    .extracting(ScheduledTask::getTask)
                    .singleElement()
                    .isInstanceOfSatisfying(
                        FixedDelayTask.class,
                        task -> {
                          assertThat(task.getRunnable())
                              .hasToString(
                                  "com.flightaggregator.bootstrap.ingestion.IngestionJob.run");
                          assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(30));
                          assertThat(task.getInitialDelayDuration()).isZero();
                        }));
  }

  /** The tests turn the schedule off and call the job themselves. */
  @Test
  void schedulesNothingWhenDisabled() {
    runner
        .withPropertyValues("ingestion.schedule.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
  }

  private static IngestionReport nothingToPublish() {
    return new IngestionReport(
        Instant.now(),
        Duration.ZERO,
        IngestionReport.Outcome.NOTHING_TO_PUBLISH,
        Optional.empty(),
        List.of());
  }
}
