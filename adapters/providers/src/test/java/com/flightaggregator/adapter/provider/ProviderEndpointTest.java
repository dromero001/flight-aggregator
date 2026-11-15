package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.domain.Provider;
import java.net.URI;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProviderEndpointTest {

  private static final URI URL = URI.create("http://localhost:1");
  private static final String BLANK_KEY = "\t\t\t";

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalid")
  void refusesAValueThatWouldLeaveTheEndpointUnusable(
      String component, Class<? extends Exception> type, ThrowingCallable construction) {
    assertThatThrownBy(construction)
        .isInstanceOf(type)
        .hasMessageContaining(component)
        .message()
        .doesNotContain(BLANK_KEY);
  }

  static Stream<Arguments> invalid() {
    return Stream.of(
        Arguments.of(
            "provider",
            NullPointerException.class,
            (ThrowingCallable) () -> new ProviderEndpoint(null, URL, "key")),
        Arguments.of(
            "baseUrl",
            NullPointerException.class,
            (ThrowingCallable) () -> new ProviderEndpoint(Provider.ALPHA, null, "key")),
        Arguments.of(
            "apiKey",
            NullPointerException.class,
            (ThrowingCallable) () -> new ProviderEndpoint(Provider.ALPHA, URL, null)),
        Arguments.of(
            "apiKey",
            IllegalArgumentException.class,
            (ThrowingCallable) () -> new ProviderEndpoint(Provider.ALPHA, URL, BLANK_KEY)));
  }
}
