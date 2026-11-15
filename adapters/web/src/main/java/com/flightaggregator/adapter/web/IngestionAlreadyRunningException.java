package com.flightaggregator.adapter.web;

/**
 * A run on demand was asked for while another run holds the ingestion's lock; {@link
 * ProblemResponses} answers it as a 409.
 */
final class IngestionAlreadyRunningException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  IngestionAlreadyRunningException() {
    super("An ingestion is already running.");
  }
}
