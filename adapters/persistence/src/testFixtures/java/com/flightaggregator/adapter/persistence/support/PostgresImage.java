package com.flightaggregator.adapter.persistence.support;

/**
 * The PostgreSQL image every Testcontainers test runs, pinned by tag and by the digest of its
 * multi-arch index (spec 6.1), so that the persistence and the application tests run the same
 * database.
 */
public final class PostgresImage {

  public static final String NAME =
      "postgres:18.4-trixie@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636";

  private PostgresImage() {}
}
