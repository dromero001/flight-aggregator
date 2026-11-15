package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.InvalidCursorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
}
