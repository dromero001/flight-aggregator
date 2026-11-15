package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProvidersPropertiesTest {

  @Test
  void neverPrintsAnApiKey() {
    ProvidersProperties properties =
        new ProvidersProperties(
            new ProvidersProperties.Endpoint(URI.create("http://alpha/"), "secret-alpha"),
            new ProvidersProperties.Endpoint(URI.create("http://beta/"), "secret-beta"),
            new ProvidersProperties.Endpoint(URI.create("http://gamma/"), "secret-gamma"),
            Duration.ofMillis(2500),
            Duration.ofSeconds(60),
            3,
            1000,
            200);

    assertThat(properties.toString())
        .doesNotContain("secret-")
        .contains("baseUrl=http://alpha/, apiKey=***", "baseUrl=http://gamma/, apiKey=***");
  }
}
