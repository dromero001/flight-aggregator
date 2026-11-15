package com.flightaggregator.bootstrap.observability;

/**
 * The logging context's key of the id that every line of one piece of background work carries: an
 * ingestion run, or a stale-route refresh (§6.10). Each piece puts a new random UUID under it, and
 * removes it when it ends. It rides the MDC, which the log framework reads, not a Scoped Value
 * (spec §6.1); in the console's JSON it appears nested, as {@code "correlation":{"id":"..."}}.
 */
public final class CorrelationId {

  public static final String MDC_KEY = "correlation.id";

  private CorrelationId() {}
}
