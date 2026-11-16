package com.flightaggregator.application.port.out;

/**
 * Why a provider's download or quote failed. {@code EMPTY_DOWNLOAD} is a download that arrived
 * whole but left no usable leg once parsed and checked against the airport catalogue (§6.5). {@code
 * REJECTED} is a call refused at once by the provider's concurrency limit (plan 5, Q9), a capacity
 * limit; {@code UNEXPECTED} is a failure of any kind the port does not name, a bug.
 */
public enum FailureReason {
  HTTP_ERROR,
  TIMEOUT,
  UNAUTHORIZED,
  CIRCUIT_OPEN,
  MALFORMED_PAGE,
  PAGINATION_LIMIT,
  EMPTY_DOWNLOAD,
  REJECTED,
  UNEXPECTED
}
