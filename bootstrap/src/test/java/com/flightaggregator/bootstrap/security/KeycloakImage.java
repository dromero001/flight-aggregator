package com.flightaggregator.bootstrap.security;

/**
 * The Keycloak image compose runs and the tests run (spec 6.1): an exact tag, at least 14 days old
 * when chosen, pinned by the digest of its multi-arch index. {@code ComposeFileTest} keeps the two
 * the same.
 */
public final class KeycloakImage {

  public static final String NAME =
      "quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332";

  private KeycloakImage() {}
}
