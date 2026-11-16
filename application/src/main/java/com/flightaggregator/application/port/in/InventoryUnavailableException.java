package com.flightaggregator.application.port.in;

/**
 * The inventory could not be read in time: the read outran its bound (the search read's statement
 * timeout), or no database connection was had within the pool's wait. Nothing is wrong with the
 * request, and the same request may succeed a little later.
 */
public final class InventoryUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InventoryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
