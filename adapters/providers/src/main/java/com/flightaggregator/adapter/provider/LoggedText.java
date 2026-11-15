package com.flightaggregator.adapter.provider;

import tools.jackson.core.io.JsonStringEncoder;

/**
 * Provider-controlled text as it may appear in an exception message: quoted, cut to 200 characters,
 * with line breaks and other control characters escaped.
 */
public final class LoggedText {

  private static final int LIMIT = 200;

  private LoggedText() {}

  /** The cut never splits a surrogate pair. */
  private static int cutAt(String text) {
    return Character.isHighSurrogate(text.charAt(LIMIT - 1)) ? LIMIT - 1 : LIMIT;
  }

  public static String quoted(String text) {
    String cut = text.length() <= LIMIT ? text : text.substring(0, cutAt(text));
    String json = new String(JsonStringEncoder.getInstance().quoteAsCharArray(cut));
    return "\"" + withoutRawControls(json) + "\"";
  }

  /** JSON escapes only the controls below space: DEL and the C1 controls are escaped here. */
  private static String withoutRawControls(String json) {
    StringBuilder out = new StringBuilder(json.length());
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (Character.isISOControl(c)) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
