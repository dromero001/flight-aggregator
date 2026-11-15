package com.flightaggregator.application.port.in;

/** Limits the public actions per client address, across every instance (§6.6). */
public interface AdmitRequest {

  /**
   * Counts the request against the client's limit for the action, and says whether it may go on.
   */
  Admission admit(String clientAddress, RateLimitedAction action);
}
