package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PagingTest {

  @Test
  void defaultsTheLimitWhenAbsent() {
    assertThat(Paging.limit(null)).isEqualTo(500);
  }

  @Test
  void readsAGivenLimit() {
    assertThat(Paging.limit("1")).isEqualTo(1);
    assertThat(Paging.limit("1000")).isEqualTo(1000);
  }

  @Test
  void rejectsALimitOutsideOneToOneThousand() {
    assertThatThrownBy(() -> Paging.limit("0")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.limit("1001")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void rejectsALimitThatIsNotANumber() {
    assertThatThrownBy(() -> Paging.limit("")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.limit("ten")).isInstanceOf(InvalidRequestException.class);
  }
}
