package com.flightaggregator.application.port.out;

/**
 * Why a provider's download failed. {@code EMPTY_DOWNLOAD} is a download that arrived whole but
 * left no usable leg once parsed and checked against the airport catalogue (§6.5).
 */
public enum FailureReason {
  HTTP_ERROR,
  TIMEOUT,
  UNAUTHORIZED,
  CIRCUIT_OPEN,
  MALFORMED_PAGE,
  PAGINATION_LIMIT,
  EMPTY_DOWNLOAD,
  UNEXPECTED
}
