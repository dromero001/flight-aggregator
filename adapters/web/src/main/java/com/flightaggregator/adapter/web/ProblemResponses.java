package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.InvalidCursorException;
import com.flightaggregator.application.port.in.InventoryUnavailableException;
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

  /** A search or a re-price whose read of the inventory was cut by its bound. */
  static final String INVENTORY_UNAVAILABLE =
      "The flights could not be read in time: try again after the seconds in Retry-After.";

  /**
   * The plan's one 503 wait (Q9: re-price's, the providers' breakers' 60 s open time), so a client
   * waits the same for either 503. A read cut by its 2 s bound means PostgreSQL is overloaded,
   * which an immediate retry would only add to.
   */
  static final Duration RETRY_INVENTORY_UNAVAILABLE = Duration.ofSeconds(60);

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

  /** A run on demand was asked for while another run holds the ingestion's lock. */
  @ExceptionHandler(IngestionAlreadyRunningException.class)
  ProblemDetail conflict(IngestionAlreadyRunningException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
  }

  /**
   * The inventory could not be read in time, for a search or a re-price (plan 5's end-of-plan
   * review, I1): the request is sound, so it is a 503 to retry, not a 500.
   */
  @ExceptionHandler(InventoryUnavailableException.class)
  ResponseEntity<ProblemDetail> inventoryUnavailable() {
    return retryAfter(
        HttpStatus.SERVICE_UNAVAILABLE, INVENTORY_UNAVAILABLE, RETRY_INVENTORY_UNAVAILABLE);
  }

  @ExceptionHandler(RetryLaterException.class)
  ResponseEntity<ProblemDetail> retryLater(RetryLaterException exception) {
    return retryAfter(exception.status(), exception.getMessage(), exception.retryAfter());
  }

  /** The client may ask again after {@code Retry-After}, in seconds. */
  private static ResponseEntity<ProblemDetail> retryAfter(
      HttpStatus status, String detail, Duration wait) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(wholeSeconds(wait)))
        .body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  /**
   * {@code Retry-After} counts whole seconds (RFC 9110); rounded up, so that a client that waits
   * them never asks too early.
   */
  private static long wholeSeconds(Duration wait) {
    return Math.ceilDiv(wait.toMillis(), 1000);
  }
}
