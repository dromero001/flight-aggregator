package com.flightaggregator.application.port.in;

/** A cursor this search cannot continue from. */
public final class InvalidCursorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidCursorException(String message) {
    super(message);
  }
}
