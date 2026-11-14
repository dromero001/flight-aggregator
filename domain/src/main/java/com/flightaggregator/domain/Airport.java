package com.flightaggregator.domain;

import java.util.regex.Pattern;

/** An airport, identified by its three-letter IATA code. */
public record Airport(String code) {

  private static final Pattern FORMAT = Pattern.compile("^[A-Z]{3}$");

  public Airport {
    if (!FORMAT.matcher(code).matches()) {
      throw new IllegalArgumentException("Airport code must be three upper-case letters: " + code);
    }
  }
}
