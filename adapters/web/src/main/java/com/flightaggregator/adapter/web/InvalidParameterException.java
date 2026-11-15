package com.flightaggregator.adapter.web;

/**
 * A query parameter whose value the API does not accept; its message names the parameter and says
 * what it takes, and {@link ProblemResponses} answers it as a 400.
 */
final class InvalidParameterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidParameterException(String message) {
    super(message);
  }
}
