package com.flightaggregator.adapter.provider;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.domain.Provider;
import java.net.URI;

/** Where a provider is and the key that opens it; the key is never printed (§6.6). */
public record ProviderEndpoint(Provider provider, URI baseUrl, String apiKey) {

  public ProviderEndpoint {
    requireNonNull(provider, "provider");
    requireNonNull(baseUrl, "baseUrl");
    if (requireNonNull(apiKey, "apiKey").isBlank()) {
      throw new IllegalArgumentException("apiKey must not be blank");
    }
  }

  @Override
  public String toString() {
    return "ProviderEndpoint[provider=" + provider + ", baseUrl=" + baseUrl + ", apiKey=***]";
  }
}
