package com.flightaggregator.mockgds.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * Where the gates stand: one in front of each provider's paths, none elsewhere. Each real
 * endpoint's key checks are in EndpointAccessTest.
 */
@MockGdsTest
class WebConfigTest {

  @Autowired private TestRestTemplate http;
  @Autowired private RandomGenerator random;

  @Test
  void passesAValidKeyThrough() {
    var response = ProviderKeys.get(http, "/gamma/probe", ProviderKeys.GAMMA);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("reached");
  }

  @Test
  void leavesPathsOutsideTheProviderPrefixesUngated() {
    assertThat(http.getForEntity("/probe", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void rejectsAWrongKeyOnAPathWithMatrixParameters() {
    var response = ProviderKeys.get(http, "/alpha;x=1/probe", ProviderKeys.BETA);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void drawsFromAThreadSafeGenerator() {
    // java.util.Random is thread-safe; the default L32X64MixRandom is not, and requests are
    // concurrent on virtual threads.
    assertThat(random).isInstanceOf(Random.class);
  }
}
