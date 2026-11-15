package com.flightaggregator.adapter.provider;

/**
 * A provider's page is not of its format, so none of the download may be used. It names no
 * provider: {@link PagedDownload} reports it as its provider's {@code MALFORMED_PAGE}.
 */
final class PageMalformed extends RuntimeException {

  private static final long serialVersionUID = 1L;

  PageMalformed(String message) {
    super(message);
  }

  PageMalformed(String message, Throwable cause) {
    super(message, cause);
  }
}
