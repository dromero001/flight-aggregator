package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.out.RefreshScheduler;
import com.flightaggregator.domain.Airport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The provisional refresh scheduler, until plan 5 Task 7 builds the background refresh. The log is
 * read as Logback events on the configuration's logger, as in {@code LoggedDiscardedRecordsTest}.
 */
class RefreshConfigTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(RefreshConfig.class);
  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void captureTheLog() {
    log.start();
    logger.addAppender(log);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(log);
  }

  /**
   * Each request is one WARN naming the origin, so a stale origin left as it is never goes unseen.
   */
  @Test
  void warnsOfEachOriginItCannotRefreshYet() {
    new ApplicationContextRunner()
        .withUserConfiguration(RefreshConfig.class)
        .run(
            context -> {
              RefreshScheduler scheduler = context.getBean(RefreshScheduler.class);

              scheduler.request(new Airport("MAD"));
              scheduler.request(new Airport("BCN"));

              assertThat(log.list)
                  .extracting(ILoggingEvent::getLevel, RefreshConfigTest::fields)
                  .containsExactly(
                      tuple(Level.WARN, "origin=MAD"), tuple(Level.WARN, "origin=BCN"));
            });
  }

  private static String fields(ILoggingEvent event) {
    return String.join(
        " ", event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList());
  }
}
