package com.flightaggregator.mockgds.serving;

/** A request the provider's strict API refuses; answered with 400. */
public class InvalidRequestException extends IllegalArgumentException {

  @java.io.Serial private static final long serialVersionUID = 1L;

  public InvalidRequestException(String message) {
    super(message);
  }

  public InvalidRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
