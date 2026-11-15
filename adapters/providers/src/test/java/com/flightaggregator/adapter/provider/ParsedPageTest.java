package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParsedPageTest {

  @Test
  void rejectsANullNextPosition() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ParsedPage(List.of(), List.of(), null))
        .withMessage("next");
  }

  @Test
  void rejectsANullAnnouncedPageCount() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ParsedPage(List.of(), List.of(), Optional.empty(), null))
        .withMessage("announcedPages");
  }

  @Test
  void announcesNoPageCountWhenBuiltWithoutOne() {
    assertThat(new ParsedPage(List.of(), List.of(), Optional.empty()).announcedPages()).isEmpty();
  }
}
