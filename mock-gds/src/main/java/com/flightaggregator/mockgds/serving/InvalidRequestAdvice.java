package com.flightaggregator.mockgds.serving;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one place an invalid paging parameter or cursor becomes a {@code 400}, for every provider.
 */
@RestControllerAdvice
class InvalidRequestAdvice {

  @ExceptionHandler(InvalidRequestException.class)
  ProblemDetail invalidRequest(InvalidRequestException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
