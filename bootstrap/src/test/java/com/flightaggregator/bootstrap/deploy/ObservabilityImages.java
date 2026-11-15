package com.flightaggregator.bootstrap.deploy;

/**
 * The telemetry images compose runs and the tests run (spec 6.1): each an exact tag, at least 14
 * days old when chosen, pinned by the digest of its multi-arch index. {@code ComposeFileTest} keeps
 * compose's images the same as these.
 */
public final class ObservabilityImages {

  /** The OpenTelemetry Collector's core distribution. */
  public static final String COLLECTOR =
      "otel/opentelemetry-collector:0.164.0@sha256:28b6d41ac51bff6b41ba5e8fe503569ffb9d6a1ea90f7a78419e86f34e44f750";

  public static final String PROMETHEUS =
      "prom/prometheus:v3.15.1@sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2";

  public static final String TEMPO =
      "grafana/tempo:3.1.1@sha256:64e2fbd56d9c2b3412a4b430a58ea59e91a670b1e75f920a0c702213ad145044";

  public static final String LOKI =
      "grafana/loki:3.7.9@sha256:0db216b630ae1f348a1584c67f3cead8b4c1fc839169a39501db664744158e28";

  public static final String GRAFANA =
      "grafana/grafana:13.3.0@sha256:488984b716c0862639f91a698adbe2862677a5a07bad42da5f03f3030883b205";

  private ObservabilityImages() {}
}
