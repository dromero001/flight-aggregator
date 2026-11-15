package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
            200,
            4);

    assertThat(properties.toString())
        .doesNotContain("secret-")
        .contains("baseUrl=http://alpha/, apiKey=***", "baseUrl=http://gamma/, apiKey=***");
  }

  @Test
  void readsTheConcurrencyLimit() {
    assertThat(bind(Map.of("providers.concurrency-limit", "3")).concurrencyLimit()).isEqualTo(3);
  }

  /** The providers' settings as Boot binds them, every endpoint set. */
  private static ProvidersProperties bind(Map<String, String> settings) {
    Map<String, String> all = new HashMap<>(settings);
    for (String provider : new String[] {"alpha", "beta", "gamma"}) {
      all.put("providers." + provider + ".base-url", "http://" + provider + ".test/");
      all.put("providers." + provider + ".api-key", provider + "-key");
    }
    return new Binder(new MapConfigurationPropertySource(all))
        .bind("providers", ProvidersProperties.class)
        .get();
  }
}
