package com.flightaggregator.adapter.provider;

import tools.jackson.core.io.JsonStringEncoder;

/**
 * Provider-controlled text as it may appear in an exception message: quoted, cut to 200 characters,
 * with line breaks and other control characters escaped.
 */
public final class LoggedText {

  private static final int LIMIT = 200;

  private LoggedText() {}

  public static String quoted(String text) {
    String cut = text.length() <= LIMIT ? text : text.substring(0, LIMIT);
    return "\"" + new String(JsonStringEncoder.getInstance().quoteAsCharArray(cut)) + "\"";
  }
}
