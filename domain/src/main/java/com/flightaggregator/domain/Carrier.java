package com.flightaggregator.domain;

import java.util.regex.Pattern;

/** An airline, identified by its two-character IATA code. */
public record Carrier(String code) {

  private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{2}$");

  public Carrier {
    if (!FORMAT.matcher(code).matches()) {
      throw new IllegalArgumentException(
          "Carrier code must be two upper-case letters or digits: " + code);
    }
  }
}
