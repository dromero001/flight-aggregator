package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.application.port.out.ItineraryWriter;
import com.flightaggregator.application.port.out.Publication;
import com.flightaggregator.application.port.out.StoreOutcome;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The log is captured as Logback events on the class's logger, so the test reads the structured
 * fields themselves, as LoggedDiscardedRecordsTest does.
 */
class CountedItineraryRepositoryTest {

  private static final Itinerary REQUOTED =
      new Itinerary(
          List.of(
              new Leg(
                  new Carrier("IB"),
                  3170,
                  new Airport("MAD"),
                  new Airport("LHR"),
                  OffsetDateTime.parse("2026-11-25T07:15:00Z"),
                  OffsetDateTime.parse("2026-11-25T09:15:00Z"),
                  Money.euros("100.00"),
                  Provider.ALPHA,
                  Instant.parse("2026-11-20T10:40:00Z"))));

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Logger logger = (Logger) LoggerFactory.getLogger(CountedItineraryRepository.class);
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

  /** Each update in place, under what became of it; the outcome passes through. */
  @Test
  void countsEachUpdateUnderItsOutcome() {
    for (StoreOutcome outcome : StoreOutcome.values()) {
      ItineraryRepository counted = new CountedItineraryRepository(answering(outcome), meters);

      assertThat(counted.updateInPlace(REQUOTED)).isEqualTo(outcome);
    }
    ItineraryRepository busy = new CountedItineraryRepository(answering(StoreOutcome.BUSY), meters);
    busy.updateInPlace(REQUOTED);

    assertThat(stores(StoreOutcome.STORED)).isEqualTo(1);
    assertThat(stores(StoreOutcome.GONE)).isEqualTo(1);
    assertThat(stores(StoreOutcome.BUSY)).isEqualTo(2);
  }

  /**
   * One WARN line for an update skipped while a run or a refresh holds the inventory, naming the
   * itinerary and no price; none for an update stored or gone.
   */
  @Test
  void logsAnUpdateSkippedWhileARunOrARefreshHoldsTheInventory() {
    for (StoreOutcome outcome : StoreOutcome.values()) {
      new CountedItineraryRepository(answering(outcome), meters).updateInPlace(REQUOTED);
    }

    assertThat(log.list)
        .extracting(
            ILoggingEvent::getLevel,
            ILoggingEvent::getFormattedMessage,
            CountedItineraryRepositoryTest::fields)
        .containsExactly(
            tuple(
                Level.WARN,
                "Skipped storing a re-price: an ingestion run or a refresh holds the inventory",
                "itinerary=" + REQUOTED.id().value()));
  }

  /**
   * A load passes through as it was answered, and counts no store: ingestion has its own meters.
   */
  @Test
  void passesALoadThroughUncounted() {
    Publication published = new Publication(2, 1, 0, Instant.parse("2026-12-28T00:00:00Z"));
    Instant now = Instant.parse("2026-11-20T10:00:00Z");
    ItineraryWriter writer = sink -> sink.accept(REQUOTED);
    ItineraryRepository loading =
        new ItineraryRepository() {
          @Override
          public Publication replaceInventory(Instant at, ItineraryWriter written) {
            assertThat(at).isEqualTo(now);
            assertThat(written).isSameAs(writer);
            return published;
          }

          @Override
          public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
            throw new UnsupportedOperationException();
          }

          @Override
          public StoreOutcome updateInPlace(Itinerary repriced) {
            throw new UnsupportedOperationException();
          }
        };

    Publication answered =
        new CountedItineraryRepository(loading, meters).replaceInventory(now, writer);

    assertThat(answered).isSameAs(published);
    assertThat(meters.find("reprice.store").counters()).isEmpty();
  }

  /**
   * A stale-route refresh's rewrite passes through as it was answered, and counts no store: the
   * background refresh logs its own report.
   */
  @Test
  void passesARefreshThroughUncounted() {
    Publication published = new Publication(3, 1, 0, Instant.parse("2026-12-28T00:00:00Z"));
    Airport madrid = new Airport("MAD");
    ItineraryWriter writer = sink -> sink.accept(REQUOTED);
    ItineraryRepository rewriting =
        new ItineraryRepository() {
          @Override
          public Publication replaceInventory(Instant now, ItineraryWriter written) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Publication replaceThrough(Airport origin, ItineraryWriter written) {
            assertThat(origin).isEqualTo(madrid);
            assertThat(written).isSameAs(writer);
            return published;
          }

          @Override
          public StoreOutcome updateInPlace(Itinerary repriced) {
            throw new UnsupportedOperationException();
          }
        };

    Publication answered =
        new CountedItineraryRepository(rewriting, meters).replaceThrough(madrid, writer);

    assertThat(answered).isSameAs(published);
    assertThat(meters.find("reprice.store").counters()).isEmpty();
  }

  private static String fields(ILoggingEvent event) {
    return String.join(
        " ", event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value).toList());
  }

  private double stores(StoreOutcome outcome) {
    return meters.get("reprice.store").tag("outcome", outcome.name()).counter().count();
  }

  /**
   * A repository whose every update in place answers that outcome; it loads and rewrites nothing.
   */
  private static ItineraryRepository answering(StoreOutcome outcome) {
    return new ItineraryRepository() {
      @Override
      public Publication replaceInventory(Instant now, ItineraryWriter writer) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Publication replaceThrough(Airport origin, ItineraryWriter writer) {
        throw new UnsupportedOperationException();
      }

      @Override
      public StoreOutcome updateInPlace(Itinerary repriced) {
        return outcome;
      }
    };
  }
}
