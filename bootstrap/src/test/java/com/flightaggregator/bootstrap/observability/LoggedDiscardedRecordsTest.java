package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The log is captured as Logback events on the class's logger, so the test reads the structured
 * fields themselves: the layout Boot's console uses depends on which Spring test ran first in the
 * JVM. FlightAggregatorApplicationTest reads the real console line.
 */
class LoggedDiscardedRecordsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Logger logger = (Logger) LoggerFactory.getLogger(LoggedDiscardedRecords.class);
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

  @Test
  void countsAndLogsEachDiscardedRecord() {
    LoggedDiscardedRecords discarded = new LoggedDiscardedRecords(meters);

    discarded.discarded(Provider.ALPHA, new DiscardedRecord(DiscardReason.PRICE, "{\"p\":-1}"));
    discarded.discarded(Provider.ALPHA, new DiscardedRecord(DiscardReason.PRICE, "{\"p\":\"x\"}"));
    discarded.discarded(Provider.GAMMA, new DiscardedRecord(DiscardReason.SHAPE, "IB;3170"));

    assertThat(count(Provider.ALPHA, DiscardReason.PRICE)).isEqualTo(2);
    assertThat(count(Provider.GAMMA, DiscardReason.SHAPE)).isEqualTo(1);
    assertThat(meters.find("ingestion.records.discarded").counters()).hasSize(2);
    assertThat(log.list)
        .extracting(ILoggingEvent::getLevel, LoggedDiscardedRecordsTest::fields)
        .containsExactly(
            tuple(Level.WARN, "provider=ALPHA reason=PRICE excerpt={\"p\":-1}"),
            tuple(Level.WARN, "provider=ALPHA reason=PRICE excerpt={\"p\":\"x\"}"),
            tuple(Level.WARN, "provider=GAMMA reason=SHAPE excerpt=IB;3170"));
  }

  private double count(Provider provider, DiscardReason reason) {
    return meters
        .get("ingestion.records.discarded")
        .tag("provider", provider.name())
        .tag("reason", reason.name())
        .counter()
        .count();
  }

  private static String fields(ILoggingEvent event) {
    return String.join(
        " ", event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList());
  }
}
