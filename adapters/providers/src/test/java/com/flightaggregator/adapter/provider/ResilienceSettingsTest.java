package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ResilienceSettingsTest {

  private static final Duration SECOND = Duration.ofSeconds(1);

  @Test
  void acceptsTheProductionValues() {
    new ResilienceSettings(
        Duration.ofMillis(2500),
        Duration.ofSeconds(60),
        3,
        Duration.ofMillis(200),
        1000,
        200,
        8_388_608);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalid")
  void refusesAValueThatWouldChangeTheMeaningOfTheSettings(
      String component, Class<? extends Exception> type, ResilienceSettingsFactory factory) {
    assertThatThrownBy(factory::create).isInstanceOf(type).hasMessageContaining(component);
  }

  static Stream<Arguments> invalid() {
    return Stream.of(
        Arguments.of(
            "requestTimeout", NullPointerException.class, f(null, SECOND, 3, SECOND, 1, 1, 1)),
        Arguments.of(
            "downloadTimeout", NullPointerException.class, f(SECOND, null, 3, SECOND, 1, 1, 1)),
        Arguments.of(
            "firstBackoff", NullPointerException.class, f(SECOND, SECOND, 3, null, 1, 1, 1)),
        Arguments.of(
            "requestTimeout",
            IllegalArgumentException.class,
            f(Duration.ZERO, SECOND, 3, SECOND, 1, 1, 1)),
        Arguments.of(
            "downloadTimeout",
            IllegalArgumentException.class,
            f(SECOND, Duration.ofSeconds(-1), 3, SECOND, 1, 1, 1)),
        Arguments.of(
            "firstBackoff",
            IllegalArgumentException.class,
            f(SECOND, SECOND, 3, Duration.ZERO, 1, 1, 1)),
        Arguments.of(
            "maxAttempts", IllegalArgumentException.class, f(SECOND, SECOND, 0, SECOND, 1, 1, 1)),
        Arguments.of(
            "pageSize", IllegalArgumentException.class, f(SECOND, SECOND, 3, SECOND, 0, 1, 1)),
        Arguments.of(
            "maxPages", IllegalArgumentException.class, f(SECOND, SECOND, 3, SECOND, 1, 0, 1)),
        Arguments.of(
            "maxBodyBytes", IllegalArgumentException.class, f(SECOND, SECOND, 3, SECOND, 1, 1, 0)),
        Arguments.of(
            "maxBodyBytes",
            IllegalArgumentException.class,
            f(SECOND, SECOND, 3, SECOND, 1, 1, Integer.MAX_VALUE)));
  }

  private static ResilienceSettingsFactory f(
      Duration request,
      Duration download,
      int attempts,
      Duration backoff,
      int pageSize,
      int pages,
      int bytes) {
    return () ->
        new ResilienceSettings(request, download, attempts, backoff, pageSize, pages, bytes);
  }

  private interface ResilienceSettingsFactory {
    ResilienceSettings create();
  }
}
