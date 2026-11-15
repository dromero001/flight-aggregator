package com.flightaggregator.adapter.web;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * A request the API cannot answer now but can later: {@link ProblemResponses} answers it with its
 * status, its detail and a {@code Retry-After} of the time to wait (§6.5, §6.6).
 */
final class RetryLaterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;
  private final Duration retryAfter;

  RetryLaterException(HttpStatus status, String detail, Duration retryAfter) {
    super(detail);
    this.status = status;
    this.retryAfter = retryAfter;
  }

  HttpStatus status() {
    return status;
  }

  Duration retryAfter() {
    return retryAfter;
  }
}
