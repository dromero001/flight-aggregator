package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.domain.Provider;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ProviderEndpointTest {

  private static final URI URL = URI.create("http://localhost:1");

  @Test
  void refusesAMissingProvider() {
    assertThatThrownBy(() -> new ProviderEndpoint(null, URL, "key"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("provider");
  }

  @Test
  void refusesAMissingBaseUrl() {
    assertThatThrownBy(() -> new ProviderEndpoint(Provider.ALPHA, null, "key"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("baseUrl");
  }

  @Test
  void refusesAMissingApiKey() {
    assertThatThrownBy(() -> new ProviderEndpoint(Provider.ALPHA, URL, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("apiKey");
  }

  @Test
  void refusesABlankApiKeyWithoutPrintingIt() {
    assertThatThrownBy(() -> new ProviderEndpoint(Provider.ALPHA, URL, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("apiKey");
  }
}
