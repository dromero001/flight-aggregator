package com.flightaggregator.adapter.persistence.support;

/**
 * The Valkey image every Testcontainers test runs: an exact tag, the newest Valkey 9 GA at least 14
 * days old when chosen, pinned by the digest of its multi-arch index (spec 6.1), so that the store
 * and the application tests run the same server.
 */
public final class ValkeyImage {

  public static final String NAME =
      "valkey/valkey:9.1.3-trixie@sha256:3add1a336a920ea54770a001ab6feb3da5fceea96a40373a3a2cd469b815f0b7";

  private ValkeyImage() {}
}
