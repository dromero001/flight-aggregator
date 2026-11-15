package com.flightaggregator.mockgds.alpha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class AlphaCursorTest {

  @Test
  void roundTripsAnOffset() {
    assertThat(AlphaCursor.encode(500)).isEqualTo("NTAw");
    assertThat(AlphaCursor.decode("NTAw")).isEqualTo(500);
  }

  @Test
  void rejectsAnUnreadableCursor() {
    assertThatIllegalArgumentException().isThrownBy(() -> AlphaCursor.decode("not base64!"));
    assertThatIllegalArgumentException().isThrownBy(() -> AlphaCursor.decode("YWJj")); // "abc"
    assertThatIllegalArgumentException().isThrownBy(() -> AlphaCursor.decode("LTE")); // "-1"
  }
}
