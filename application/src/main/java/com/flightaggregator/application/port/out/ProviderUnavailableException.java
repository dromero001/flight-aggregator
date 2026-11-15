package com.flightaggregator.application.port.out;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.domain.Provider;

/** A provider's download did not complete: no page of it may be used. */
public class ProviderUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Provider provider;
  private final FailureReason reason;

  public ProviderUnavailableException(Provider provider, FailureReason reason, Throwable cause) {
    super(
        requireNonNull(provider, "provider") + " unavailable: " + requireNonNull(reason, "reason"),
        cause);
    this.provider = provider;
    this.reason = reason;
  }

  public Provider provider() {
    return provider;
  }

  public FailureReason reason() {
    return reason;
  }
}
