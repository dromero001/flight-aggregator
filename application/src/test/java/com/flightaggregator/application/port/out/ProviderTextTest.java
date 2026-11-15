package com.flightaggregator.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderTextTest {

  @Test
  void writesEveryControlCharacterAsALowercaseUnicodeEscape() {
    assertThat(ProviderText.escaped("a\nb\r\tc\u0001\u007f\u0085\u009f"))
        .isEqualTo("a\\u000ab\\u000d\\u0009c\\u0001\\u007f\\u0085\\u009f");
  }

  /** Probes: a literal backslash-n, a real line break, and text that looks like an escape. */
  @Test
  void doublesABackslashSoProviderTextCannotPassForAnEscape() {
    assertThat(ProviderText.escaped("a\\nb")).isEqualTo("a\\\\nb");
    assertThat(ProviderText.escaped("a\nb")).isEqualTo("a\\u000ab");
    assertThat(ProviderText.escaped("aaa\\u0")).isEqualTo("aaa\\\\u0");
  }

  @Test
  void cutsTheEscapedTextAtTwoHundredCharacters() {
    assertThat(ProviderText.escaped("a".repeat(200))).isEqualTo("a".repeat(200));
    assertThat(ProviderText.escaped("a".repeat(300))).isEqualTo("a".repeat(200));
  }

  /** Probe: text ending in a backslash, and a control character, each across the limit. */
  @Test
  void neverCutsInsideAnEscape() {
    assertThat(ProviderText.escaped("a".repeat(199) + "\\")).isEqualTo("a".repeat(199));
    assertThat(ProviderText.escaped("a".repeat(196) + "\u0001")).isEqualTo("a".repeat(196));
    assertThat(ProviderText.escaped("a".repeat(194) + "\u0001"))
        .isEqualTo("a".repeat(194) + "\\u0001");
  }

  @Test
  void neverSplitsASurrogatePair() {
    assertThat(ProviderText.escaped("a".repeat(199) + "😀tail")).isEqualTo("a".repeat(199));
    assertThat(ProviderText.escaped("a".repeat(198) + "😀tail")).isEqualTo("a".repeat(198) + "😀");
  }

  /** Quoting comes after the cut, so the closing quote is never cut away. */
  @Test
  void quotesTheEscapedText() {
    assertThat(ProviderText.quoted("a\r\nERROR forged"))
        .isEqualTo("\"a\\u000d\\u000aERROR forged\"");
    assertThat(ProviderText.quoted("a".repeat(300))).isEqualTo("\"" + "a".repeat(200) + "\"");
  }
}
