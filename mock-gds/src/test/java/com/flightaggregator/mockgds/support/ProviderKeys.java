package com.flightaggregator.mockgds.support;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The keys {@link MockGdsTest} configures, and a GET that presents one. */
public final class ProviderKeys {

  public static final String ALPHA = "test-alpha-key";
  public static final String BETA = "test-beta-key";
  public static final String GAMMA = "test-gamma-key";

  private ProviderKeys() {}

  public static ResponseEntity<String> get(TestRestTemplate http, String path, String apiKey) {
    var headers = new HttpHeaders();
    headers.set("X-Api-Key", apiKey);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
