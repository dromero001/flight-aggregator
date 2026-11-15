package com.flightaggregator.application.port.out;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Provider-controlled text made safe for a log line or an exception message (§6.7, §6.10), the one
 * mechanism for every excerpt and message that carries it. Every backslash is doubled and every ISO
 * control character is written as a Unicode escape (a backslash, {@code u} and four lowercase hex
 * digits), so provider text can neither forge a line nor pass for an escape: a literal backslash
 * and {@code n} stays apart from a line break. The escaped text is then cut to at most 200
 * characters, never inside an escape or a surrogate pair. {@link #escaped} returns that text, at
 * most 200 characters; {@link #quoted} also writes a double quote inside the value as a backslash
 * and a quote, and returns the text between two double quotes, so at most 202 characters.
 */
public final class ProviderText {

  private static final int LIMIT = 200;

  private ProviderText() {}

  /** The text escaped, then cut. */
  public static String escaped(String text) {
    return cut(text, ProviderText::escape);
  }

  /**
   * The escaped text in double quotes, for a value inside a message. A double quote inside the
   * value is written as a backslash and a quote, so where the value ends is unambiguous; the
   * excerpts of {@link #escaped} keep it as is.
   */
  public static String quoted(String text) {
    return "\"" + cut(text, ProviderText::escapeInQuotes) + "\"";
  }

  private static String cut(String text, IntFunction<String> escape) {
    StringBuilder out = new StringBuilder(Math.min(text.length(), LIMIT));
    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      String escaped = escape.apply(codePoint);
      if (out.length() + escaped.length() > LIMIT) {
        break;
      }
      out.append(escaped);
      i += Character.charCount(codePoint);
    }
    return out.toString();
  }

  private static String escapeInQuotes(int codePoint) {
    return codePoint == '"' ? "\\\"" : escape(codePoint);
  }

  private static String escape(int codePoint) {
    if (codePoint == '\\') {
      return "\\\\";
    }
    if (Character.isISOControl(codePoint)) {
      return String.format(Locale.ROOT, "\\u%04x", codePoint);
    }
    return Character.toString(codePoint);
  }
}
