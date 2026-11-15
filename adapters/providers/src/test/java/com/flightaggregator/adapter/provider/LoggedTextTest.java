package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoggedTextTest {

  @Test
  void quotesAndEscapesLineBreaksSoOneValueStaysOnOneLogLine() {
    assertThat(LoggedText.quoted("a\r\nERROR forged\tline"))
        .isEqualTo("\"a\\r\\nERROR forged\\tline\"");
  }

  @Test
  void cutsTheTextAtTwoHundredCharacters() {
    assertThat(LoggedText.quoted("a".repeat(500))).isEqualTo("\"" + "a".repeat(200) + "\"");
  }

  @Test
  void neverSplitsASurrogatePairWhenCutting() {
    assertThat(LoggedText.quoted("a".repeat(199) + "😀" + "tail"))
        .isEqualTo("\"" + "a".repeat(199) + "\"");
  }

  @Test
  void escapesDelAndTheC1ControlCharactersToo() {
    assertThat(LoggedText.quoted("a\u0085b\u007fc\u009fd"))
        .isEqualTo("\"a\\u0085b\\u007fc\\u009fd\"");
  }
}
