package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.InvalidCursorException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The API's errors as RFC 9457 problem details, which Spring writes as {@code
 * application/problem+json} with the request's path as their {@code instance}. Only the errors
 * named here are mapped: any other exception stays Boot's 500, never a 400 by accident (a domain
 * {@code IllegalArgumentException} included).
 */
@RestControllerAdvice
public final class ProblemResponses {

  /** A parameter or a cursor the client sent is not valid; the message names it. */
  @ExceptionHandler({InvalidParameterException.class, InvalidCursorException.class})
  ProblemDetail badRequest(RuntimeException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  /** The path names an itinerary the inventory does not hold. */
  @ExceptionHandler(ItineraryNotFoundException.class)
  ProblemDetail notFound(ItineraryNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  /** The client may ask again after {@code Retry-After}, in seconds. */
  @ExceptionHandler(RetryLaterException.class)
  ResponseEntity<ProblemDetail> retryLater(RetryLaterException exception) {
    return ResponseEntity.status(exception.status())
        .header(HttpHeaders.RETRY_AFTER, Long.toString(wholeSeconds(exception.retryAfter())))
        .body(ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage()));
  }

  /**
   * {@code Retry-After} counts whole seconds (RFC 9110); rounded up, so that a client that waits
   * them never asks too early.
   */
  private static long wholeSeconds(Duration wait) {
    return Math.ceilDiv(wait.toMillis(), 1000);
  }
}
