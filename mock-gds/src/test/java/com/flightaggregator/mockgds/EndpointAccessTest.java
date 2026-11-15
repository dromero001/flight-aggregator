package com.flightaggregator.mockgds;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Who reaches what: each real endpoint only with its own provider's key, and Actuator only health.
 */
@MockGdsTest
class EndpointAccessTest {

  @Autowired private TestRestTemplate http;

  /** A real endpoint, its provider's key, and another provider's key. */
  private record Endpoint(String path, String key, String otherKey) {

    @Override
    public String toString() {
      return path;
    }
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        new Endpoint("/alpha/legs", ProviderKeys.ALPHA, ProviderKeys.BETA),
        new Endpoint("/beta/segments", ProviderKeys.BETA, ProviderKeys.GAMMA),
        new Endpoint("/gamma/legs.csv", ProviderKeys.GAMMA, ProviderKeys.ALPHA));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void rejectsAMissingKey(Endpoint endpoint) {
    assertUnauthorizedWithoutAnyKeyInTheBody(http.getForEntity(endpoint.path(), String.class));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void rejectsAnotherProvidersKey(Endpoint endpoint) {
    assertUnauthorizedWithoutAnyKeyInTheBody(
        ProviderKeys.get(http, endpoint.path(), endpoint.otherKey()));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void servesItsOwnProvidersKey(Endpoint endpoint) {
    assertThat(ProviderKeys.get(http, endpoint.path(), endpoint.key()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/env", "/actuator/configprops"})
  void exposesNoActuatorEndpointButHealth(String path) {
    assertThat(http.getForEntity(path, String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static void assertUnauthorizedWithoutAnyKeyInTheBody(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(Objects.toString(response.getBody(), ""))
        .doesNotContain(ProviderKeys.ALPHA, ProviderKeys.BETA, ProviderKeys.GAMMA);
  }
}
