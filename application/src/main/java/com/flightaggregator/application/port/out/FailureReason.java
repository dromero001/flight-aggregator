package com.flightaggregator.application.port.out;

/** Why a provider's download failed. */
public enum FailureReason {
  HTTP_ERROR,
  TIMEOUT,
  UNAUTHORIZED,
  CIRCUIT_OPEN,
  MALFORMED_PAGE,
  PAGINATION_LIMIT,
  UNEXPECTED
}
