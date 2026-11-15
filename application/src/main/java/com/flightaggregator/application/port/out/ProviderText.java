package com.flightaggregator.application.port.out;

import java.util.Locale;

/**
 * Provider-controlled text made safe for a log line or an exception message (§6.7, §6.10), the one
 * mechanism for every excerpt and message that carries it. Every backslash is doubled and every ISO
 * control character is written as a Unicode escape (a backslash, {@code u} and four lowercase hex
 * digits), so provider text can neither forge a line nor pass for an escape: a literal backslash
 * and {@code n} stays apart from a line break. The escaped text is then cut to at most 200
 * characters, never inside an escape or a surrogate pair. {@link #quoted} also escapes a double
 * quote inside the value, as a backslash and a quote.
 */
public final class ProviderText {

  private static final int LIMIT = 200;

  private ProviderText() {}

  /** The text escaped, then cut. */
  public static String escaped(String text) {
    return escapedAndCut(text, false);
  }

  /**
   * The escaped text in double quotes, for a value inside a message. A double quote inside the
   * value is written as a backslash and a quote, so where the value ends is unambiguous; the
   * excerpts of {@link #escaped} keep it as is.
   */
  public static String quoted(String text) {
    return "\"" + escapedAndCut(text, true) + "\"";
  }

  private static String escapedAndCut(String text, boolean escapeDoubleQuote) {
    StringBuilder out = new StringBuilder(Math.min(text.length(), LIMIT));
    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      String escape = escape(codePoint, escapeDoubleQuote);
      if (out.length() + escape.length() > LIMIT) {
        break;
      }
      out.append(escape);
      i += Character.charCount(codePoint);
    }
    return out.toString();
  }

  private static String escape(int codePoint, boolean escapeDoubleQuote) {
    if (codePoint == '\\') {
      return "\\\\";
    }
    if (codePoint == '"' && escapeDoubleQuote) {
      return "\\\"";
    }
    if (Character.isISOControl(codePoint)) {
      return String.format(Locale.ROOT, "\\u%04x", codePoint);
    }
    return Character.toString(codePoint);
  }
}
