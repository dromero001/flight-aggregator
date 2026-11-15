package com.flightaggregator.adapter.provider;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.DiscardReason;

/** A record of a provider's page is not valid: it is discarded with {@link #reason()}. */
public final class RecordRejected extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final DiscardReason reason;

  public RecordRejected(DiscardReason reason) {
    super(requireNonNull(reason, "reason").name());
    this.reason = reason;
  }

  public DiscardReason reason() {
    return reason;
  }
}
